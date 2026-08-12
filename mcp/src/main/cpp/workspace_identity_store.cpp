#include "workspace_identity_store.hpp"

#include "config_document.hpp"
#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <nlohmann/json.hpp>

#include <cstddef>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace vibris::mcp {
namespace {

using Json = nlohmann::json;

[[noreturn]] void invalid_identity() {
    throw StateError(kInvalidConfigCode, "Workspace identity is malformed.");
}

[[noreturn]] void unsupported_identity() {
    throw StateError(kUnsupportedVersionCode, "UNSUPPORTED_VERSION: workspace identity schema_version must be 2.");
}

class UniqueHandle final {
public:
    explicit UniqueHandle(HANDLE handle) noexcept : handle_(handle) {
    }

    UniqueHandle(const UniqueHandle&) = delete;
    UniqueHandle& operator=(const UniqueHandle&) = delete;

    UniqueHandle(UniqueHandle&& other) noexcept
        : handle_(std::exchange(other.handle_, INVALID_HANDLE_VALUE)) {
    }

    UniqueHandle& operator=(UniqueHandle&&) = delete;

    ~UniqueHandle() {
        if (handle_ != INVALID_HANDLE_VALUE) {
            CloseHandle(handle_);
        }
    }

    [[nodiscard]] HANDLE get() const noexcept {
        return handle_;
    }

    [[nodiscard]] bool close() noexcept {
        if (handle_ == INVALID_HANDLE_VALUE) {
            return true;
        }
        if (!CloseHandle(handle_)) {
            return false;
        }
        handle_ = INVALID_HANDLE_VALUE;
        return true;
    }

private:
    HANDLE handle_;
};

bool missing_error(const DWORD error) {
    return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND;
}

[[noreturn]] void state_io_error(std::string message) {
    throw StateError(kConfigIoErrorCode, std::move(message), true);
}

class StateDirectoryGuard final {
public:
    explicit StateDirectoryGuard(const std::filesystem::path& path)
        : handle_(open(path)) {
        FILE_ATTRIBUTE_TAG_INFO information{};
        if (!GetFileInformationByHandleEx(
                handle_.get(), FileAttributeTagInfo, &information, sizeof(information)) ||
            GetFileType(handle_.get()) != FILE_TYPE_DISK ||
            (information.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0 ||
            (information.FileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DEVICE)) != 0) {
            state_io_error("The workspace state directory is not an ordinary local directory.");
        }
    }

private:
    static HANDLE open(const std::filesystem::path& path) {
        if (!CreateDirectoryW(path.c_str(), nullptr)) {
            const auto error = GetLastError();
            if (error != ERROR_ALREADY_EXISTS) {
                state_io_error("Unable to create the workspace state directory.");
            }
        }
        const auto handle = CreateFileW(path.c_str(),
            FILE_ADD_FILE | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (handle == INVALID_HANDLE_VALUE) {
            state_io_error("Unable to open the workspace state directory.");
        }
        return handle;
    }

    UniqueHandle handle_;
};

enum class StateFileKind {
    missing,
    busy,
    nonregular,
    content,
};

struct StateFile final {
    StateFileKind kind;
    std::string content;
};

class DuplicateKeyError final {
};

class JsonDepthError final {
};

StateFile read_state_handle(HANDLE input, std::string_view description) {
    FILE_ATTRIBUTE_TAG_INFO information{};
    if (!GetFileInformationByHandleEx(
            input, FileAttributeTagInfo, &information, sizeof(information))) {
        state_io_error("Unable to inspect the " + std::string(description) + ".");
    }
    if (GetFileType(input) != FILE_TYPE_DISK ||
        (information.FileAttributes &
            (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_DEVICE)) != 0) {
        return {StateFileKind::nonregular, {}};
    }

    LARGE_INTEGER size{};
    if (!GetFileSizeEx(input, &size)) {
        state_io_error("Unable to size the " + std::string(description) + ".");
    }
    if (size.QuadPart < 0 ||
        static_cast<unsigned long long>(size.QuadPart) > static_cast<unsigned long long>(kMaxConfigJsonBytes)) {
        return {StateFileKind::nonregular, {}};
    }
    LARGE_INTEGER beginning{};
    if (!SetFilePointerEx(input, beginning, nullptr, FILE_BEGIN)) {
        state_io_error("Unable to seek the " + std::string(description) + ".");
    }
    std::string text(static_cast<std::size_t>(size.QuadPart), '\0');
    std::size_t offset = 0;
    while (offset < text.size()) {
        DWORD count = 0;
        const auto remaining = static_cast<DWORD>(text.size() - offset);
        if (!ReadFile(input, text.data() + offset, remaining, &count, nullptr) || count == 0) {
            state_io_error("Unable to read the " + std::string(description) + ".");
        }
        offset += count;
    }
    return {StateFileKind::content, std::move(text)};
}

StateFile read_state_file(const std::filesystem::path& path, std::string_view description) {
    UniqueHandle input(CreateFileW(path.c_str(), GENERIC_READ,
        FILE_SHARE_READ, nullptr, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_SEQUENTIAL_SCAN, nullptr));
    if (input.get() == INVALID_HANDLE_VALUE) {
        const auto error = GetLastError();
        if (missing_error(error)) {
            return {StateFileKind::missing, {}};
        }
        if (error == ERROR_SHARING_VIOLATION || error == ERROR_LOCK_VIOLATION) {
            return {StateFileKind::busy, {}};
        }
        state_io_error("Unable to open the " + std::string(description) + ".");
    }
    return read_state_handle(input.get(), description);
}

Json parse_json_without_duplicate_keys(std::string_view text) {
    std::unordered_map<int, std::unordered_set<std::string>> object_keys;
    Json::parser_callback_t callback = [&](int depth, Json::parse_event_t event, Json& parsed) {
        if ((event == Json::parse_event_t::object_start || event == Json::parse_event_t::array_start) &&
            depth >= static_cast<int>(detail::kMaxWorkspaceIdentityJsonNestingDepth)) {
            throw JsonDepthError{};
        }
        if (event == Json::parse_event_t::object_start) {
            object_keys[depth].clear();
        } else if (event == Json::parse_event_t::key) {
            const auto object = object_keys.find(depth - 1);
            if (object == object_keys.end() ||
                !object->second.insert(parsed.get<std::string>()).second) {
                throw DuplicateKeyError{};
            }
        } else if (event == Json::parse_event_t::object_end) {
            object_keys.erase(depth);
        }
        return true;
    };
    return Json::parse(text.begin(), text.end(), callback);
}

std::string parse_identity(std::string_view text) {
    try {
        const auto document = parse_json_without_duplicate_keys(text);
        if (!document.is_object() || document.size() != 2 || !document.contains("schema_version") ||
            !document.contains("workspace_id") || !document.at("schema_version").is_number_unsigned() ||
            !document.at("workspace_id").is_string()) {
            invalid_identity();
        }
        if (document.at("schema_version").get<std::uint64_t>() != 2) {
            unsupported_identity();
        }
        const auto workspace_id = document.at("workspace_id").get<std::string>();
        if (!detail::is_uuid(workspace_id)) {
            invalid_identity();
        }
        return workspace_id;
    } catch (const StateError&) {
        throw;
    } catch (const DuplicateKeyError&) {
        invalid_identity();
    } catch (const JsonDepthError&) {
        invalid_identity();
    } catch (const Json::exception&) {
        invalid_identity();
    }
}

std::string serialize_identity(std::string_view workspace_id) {
    return Json{{"schema_version", 2}, {"workspace_id", workspace_id}}.dump(2);
}

void notify_before(detail::WorkspaceIdentityIoHooks* hooks, detail::WorkspaceIdentityIoOperation operation,
    const std::filesystem::path& temporary_path, const std::filesystem::path& identity_path) noexcept {
    if (hooks) {
        hooks->before(operation, temporary_path, identity_path);
    }
}

std::optional<DWORD> injected_error(detail::WorkspaceIdentityIoHooks* hooks,
    detail::WorkspaceIdentityIoOperation operation, const std::filesystem::path& temporary_path,
    const std::filesystem::path& identity_path) noexcept {
    if (!hooks) {
        return std::nullopt;
    }
    if (const auto error = hooks->injected_error(operation, temporary_path, identity_path)) {
        return static_cast<DWORD>(*error);
    }
    return std::nullopt;
}

void notify_after(detail::WorkspaceIdentityIoHooks* hooks, detail::WorkspaceIdentityIoOperation operation,
    const std::filesystem::path& temporary_path, const std::filesystem::path& identity_path,
    bool success, DWORD error) noexcept {
    if (hooks) {
        hooks->after(operation, temporary_path, identity_path, success, error);
    }
}

class PendingTempFile final {
public:
    PendingTempFile(UniqueHandle handle, std::filesystem::path path,
        const std::filesystem::path& identity_path, detail::WorkspaceIdentityIoHooks* hooks) noexcept
        : handle_(std::move(handle)), path_(std::move(path)),
          identity_path_(&identity_path), hooks_(hooks) {
    }

    PendingTempFile(const PendingTempFile&) = delete;
    PendingTempFile& operator=(const PendingTempFile&) = delete;

    ~PendingTempFile() {
        if (!published_ && handle_.get() != INVALID_HANDLE_VALUE) {
            FILE_DISPOSITION_INFO disposition{TRUE};
            SetFileInformationByHandle(
                handle_.get(), FileDispositionInfo, &disposition, sizeof(disposition));
        }
    }

    [[nodiscard]] const std::filesystem::path& path() const noexcept {
        return path_;
    }

    [[nodiscard]] HANDLE handle() const noexcept {
        return handle_.get();
    }

    void remove_checked() {
        if (handle_.get() == INVALID_HANDLE_VALUE || published_) {
            return;
        }
        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::cleanup, path_, *identity_path_);
        const auto forced = injected_error(
            hooks_, detail::WorkspaceIdentityIoOperation::cleanup, path_, *identity_path_);
        bool success = false;
        DWORD error = ERROR_SUCCESS;
        if (forced) {
            error = *forced;
        } else {
            FILE_DISPOSITION_INFO disposition{TRUE};
            if (!SetFileInformationByHandle(
                    handle_.get(), FileDispositionInfo, &disposition, sizeof(disposition))) {
                error = GetLastError();
            } else if (!handle_.close()) {
                error = GetLastError();
            } else {
                success = true;
            }
        }
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::cleanup, path_, *identity_path_, success, error);
        if (!success) {
            state_io_error("Unable to remove the temporary workspace identity.");
        }
    }

    void published() noexcept {
        published_ = true;
    }

    void close_published_checked() {
        if (!handle_.close()) {
            state_io_error("Unable to close the published workspace identity.");
        }
    }

private:
    UniqueHandle handle_;
    std::filesystem::path path_;
    const std::filesystem::path* identity_path_;
    detail::WorkspaceIdentityIoHooks* hooks_;
    bool published_ = false;
};

bool rename_by_handle(HANDLE file, std::wstring_view destination) {
    const auto filename_bytes = destination.size() * sizeof(wchar_t);
    const auto prefix_bytes = FIELD_OFFSET(FILE_RENAME_INFO, FileName);
    if (filename_bytes > std::numeric_limits<DWORD>::max() - prefix_bytes - sizeof(wchar_t)) {
        SetLastError(ERROR_FILENAME_EXCED_RANGE);
        return false;
    }
    const auto information_size = prefix_bytes + filename_bytes + sizeof(wchar_t);
    auto storage = std::make_unique<std::byte[]>(information_size);
    std::memset(storage.get(), 0, information_size);
    auto* information = reinterpret_cast<FILE_RENAME_INFO*>(storage.get());
    information->ReplaceIfExists = FALSE;
    information->RootDirectory = nullptr;
    information->FileNameLength = static_cast<DWORD>(filename_bytes);
    std::memcpy(information->FileName, destination.data(), filename_bytes);
    return SetFileInformationByHandle(
        file, FileRenameInfo, information, static_cast<DWORD>(information_size)) != 0;
}

}

void detail::WorkspaceIdentityIoHooks::before(
    WorkspaceIdentityIoOperation, const std::filesystem::path&, const std::filesystem::path&) noexcept {
}

std::optional<std::uint32_t> detail::WorkspaceIdentityIoHooks::injected_error(
    WorkspaceIdentityIoOperation, const std::filesystem::path&, const std::filesystem::path&) noexcept {
    return std::nullopt;
}

void detail::WorkspaceIdentityIoHooks::after(WorkspaceIdentityIoOperation, const std::filesystem::path&,
    const std::filesystem::path&, bool, std::uint32_t) noexcept {
}

WorkspaceIdentityStore::WorkspaceIdentityStore(std::filesystem::path identity_path)
    : identity_path_(std::move(identity_path)) {
}

WorkspaceIdentityStore::WorkspaceIdentityStore(std::filesystem::path identity_path,
    detail::WorkspaceIdentityIoHooks& hooks)
    : identity_path_(std::move(identity_path)), hooks_(&hooks) {
}

const std::filesystem::path& WorkspaceIdentityStore::path() const noexcept {
    return identity_path_;
}

std::string WorkspaceIdentityStore::load_or_create() const {
    StateDirectoryGuard state_directory(identity_path_.parent_path());
    static_cast<void>(state_directory);
    if (const auto existing = load_existing()) {
        return *existing;
    }
    return publish(detail::generate_uuid());
}

std::optional<std::string> WorkspaceIdentityStore::load_existing() const {
    const auto file = read_state_file(identity_path_, "workspace identity");
    if (file.kind == StateFileKind::missing) {
        return std::nullopt;
    }
    if (file.kind == StateFileKind::busy) {
        state_io_error("The workspace identity is busy.");
    }
    if (file.kind == StateFileKind::nonregular || file.content.empty()) {
        invalid_identity();
    }
    return parse_identity(file.content);
}

std::optional<std::string> WorkspaceIdentityStore::load_existing_after_publish_loss() const {
    std::size_t retries = 0;
    while (true) {
        const auto file = read_state_file(identity_path_, "published workspace identity winner");
        if (file.kind != StateFileKind::busy) {
            if (file.kind == StateFileKind::missing) {
                return std::nullopt;
            }
            if (file.kind == StateFileKind::nonregular || file.content.empty()) {
                invalid_identity();
            }
            return parse_identity(file.content);
        }
        if (retries == detail::kMaxWorkspaceIdentityWinnerReadRetries) {
            state_io_error("The published workspace identity winner is busy.");
        }
        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::winner_retry, {}, identity_path_);
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::winner_retry, {}, identity_path_,
            true, ERROR_SHARING_VIOLATION);
        Sleep(1);
        ++retries;
    }
}

std::string WorkspaceIdentityStore::publish(std::string workspace_id) const {
    StateDirectoryGuard state_directory(identity_path_.parent_path());
    static_cast<void>(state_directory);
    std::error_code canonical_error;
    const auto canonical_state_directory =
        std::filesystem::canonical(identity_path_.parent_path(), canonical_error);
    if (canonical_error || !canonical_state_directory.is_absolute()) {
        state_io_error("Unable to resolve the workspace identity destination.");
    }
    const auto rename_destination =
        (canonical_state_directory / identity_path_.filename()).lexically_normal();
    const auto text = serialize_identity(workspace_id);
    std::optional<PendingTempFile> temp;
    for (std::size_t attempt = 0;
        attempt < detail::kMaxWorkspaceIdentityTempCreateAttempts; ++attempt) {
        auto candidate = std::filesystem::path(identity_path_.wstring() + L".tmp." +
            std::filesystem::path(detail::generate_uuid()).wstring());
        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::create, candidate, identity_path_);
        const auto forced_create = injected_error(
            hooks_, detail::WorkspaceIdentityIoOperation::create, candidate, identity_path_);
        UniqueHandle created(forced_create ? INVALID_HANDLE_VALUE : CreateFileW(
            candidate.c_str(), GENERIC_READ | GENERIC_WRITE | DELETE, FILE_SHARE_READ, nullptr, CREATE_NEW,
            FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
        const auto create_success = created.get() != INVALID_HANDLE_VALUE;
        const auto create_error = create_success ? ERROR_SUCCESS :
            forced_create ? *forced_create : GetLastError();
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::create, candidate, identity_path_,
            create_success, create_error);
        if (create_success) {
            temp.emplace(std::move(created), std::move(candidate), identity_path_, hooks_);
            break;
        }
        if (create_error != ERROR_FILE_EXISTS && create_error != ERROR_ALREADY_EXISTS) {
            state_io_error("Unable to create the temporary workspace identity.");
        }
    }
    if (!temp) {
        state_io_error("Unable to allocate a unique temporary workspace identity.");
    }

    try {
        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::write, temp->path(), identity_path_);
        const auto forced_write = injected_error(
            hooks_, detail::WorkspaceIdentityIoOperation::write, temp->path(), identity_path_);
        DWORD count = 0;
        const auto write_success = forced_write ? false :
            WriteFile(temp->handle(), text.data(), static_cast<DWORD>(text.size()), &count, nullptr) != 0 &&
                count == static_cast<DWORD>(text.size());
        const auto write_error = write_success ? ERROR_SUCCESS :
            forced_write ? *forced_write : GetLastError();
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::write, temp->path(), identity_path_,
            write_success, write_error);
        if (!write_success) {
            state_io_error("Unable to write the temporary workspace identity.");
        }

        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::flush, temp->path(), identity_path_);
        const auto forced_flush = injected_error(
            hooks_, detail::WorkspaceIdentityIoOperation::flush, temp->path(), identity_path_);
        const auto flush_success = forced_flush ? false : FlushFileBuffers(temp->handle()) != 0;
        const auto flush_error = flush_success ? ERROR_SUCCESS :
            forced_flush ? *forced_flush : GetLastError();
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::flush, temp->path(), identity_path_,
            flush_success, flush_error);
        if (!flush_success) {
            state_io_error("Unable to flush the temporary workspace identity.");
        }

        notify_before(hooks_, detail::WorkspaceIdentityIoOperation::move, temp->path(), identity_path_);
        const auto forced_move = injected_error(
            hooks_, detail::WorkspaceIdentityIoOperation::move, temp->path(), identity_path_);
        const auto move_success = forced_move ? false :
            rename_by_handle(temp->handle(), rename_destination.wstring());
        const auto move_error = move_success ? ERROR_SUCCESS :
            forced_move ? *forced_move : GetLastError();
        notify_after(hooks_, detail::WorkspaceIdentityIoOperation::move, temp->path(), identity_path_,
            move_success, move_error);
        if (move_success) {
            temp->published();
            const auto published = read_state_handle(temp->handle(), "published workspace identity");
            if (published.kind != StateFileKind::content || published.content.empty()) {
                invalid_identity();
            }
            const auto published_id = parse_identity(published.content);
            if (published_id != workspace_id) {
                invalid_identity();
            }
            temp->close_published_checked();
            return published_id;
        }

        temp->remove_checked();
        const auto winner = load_existing_after_publish_loss();
        if (winner) {
            return *winner;
        }
        state_io_error("Unable to publish the workspace identity (Windows error " +
            std::to_string(move_error) + ").");
    } catch (...) {
        const auto failure = std::current_exception();
        temp->remove_checked();
        std::rethrow_exception(failure);
    }
}

}
