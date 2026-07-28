#include "config_store.hpp"

#include "config_document.hpp"
#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <fstream>
#include <string>
#include <system_error>
#include <utility>

namespace vibris::mcp {
namespace {

class UniqueHandle final {
public:
    explicit UniqueHandle(HANDLE handle) noexcept : handle_(handle) {
    }

    UniqueHandle(const UniqueHandle&) = delete;
    UniqueHandle& operator=(const UniqueHandle&) = delete;

    ~UniqueHandle() {
        if (handle_ != INVALID_HANDLE_VALUE) {
            CloseHandle(handle_);
        }
    }

    [[nodiscard]] HANDLE get() const noexcept {
        return handle_;
    }

    [[nodiscard]] bool close() noexcept {
        const auto handle = std::exchange(handle_, INVALID_HANDLE_VALUE);
        return CloseHandle(handle) != 0;
    }

private:
    HANDLE handle_;
};

class PendingTempFile final {
public:
    explicit PendingTempFile(std::filesystem::path path) : path_(std::move(path)) {
    }

    PendingTempFile(const PendingTempFile&) = delete;
    PendingTempFile& operator=(const PendingTempFile&) = delete;

    ~PendingTempFile() {
        if (!path_.empty()) {
            DeleteFileW(path_.c_str());
        }
    }

    [[nodiscard]] const std::filesystem::path& path() const noexcept {
        return path_;
    }

    void committed() noexcept {
        path_.clear();
    }

private:
    std::filesystem::path path_;
};

} // namespace

ConfigStore::ConfigStore(std::filesystem::path config_path) : config_path_(std::move(config_path)) {
}

const std::filesystem::path& ConfigStore::path() const noexcept {
    return config_path_;
}

std::optional<SessionConfig> ConfigStore::load() const {
    std::error_code error;
    if (!std::filesystem::exists(config_path_, error)) {
        if (error) {
            throw StateError(kConfigIoErrorCode, "Unable to inspect the persisted config.", true);
        }
        return std::nullopt;
    }

    std::ifstream input(config_path_, std::ios::binary);
    if (!input) {
        throw StateError(kConfigIoErrorCode, "Unable to open the persisted config.", true);
    }
    std::string text(kMaxConfigJsonBytes + 1, '\0');
    input.read(text.data(), static_cast<std::streamsize>(text.size()));
    const auto size = input.gcount();
    if (input.bad()) {
        throw StateError(kConfigIoErrorCode, "Unable to read the persisted config.", true);
    }
    if (size > static_cast<std::streamsize>(kMaxConfigJsonBytes)) {
        throw StateError(kRequestTooLargeCode, "Persisted config exceeds the 64 KiB limit.");
    }
    text.resize(static_cast<std::size_t>(size));
    return detail::parse_config(text, detail::ConfigDocumentKind::persisted);
}

SessionConfig ConfigStore::prepare_for_save(SessionConfig config) const {
    const auto existing = load();
    if (existing) {
        config.workspace_id = existing->workspace_id;
    } else if (config.workspace_id.empty()) {
        config.workspace_id = detail::generate_uuid();
    }
    detail::validate_config(config);
    return config;
}

void ConfigStore::save(const SessionConfig& config) const {
    write_atomic(prepare_for_save(config));
}

SessionConfig ConfigStore::save_json(std::string_view json_text) const {
    auto config = prepare_for_save(detail::parse_config(json_text, detail::ConfigDocumentKind::configure_request));
    write_atomic(config);
    return config;
}

void ConfigStore::write_atomic(const SessionConfig& config) const {
    const auto text = detail::serialize_config(config);
    std::error_code error;
    std::filesystem::create_directories(config_path_.parent_path(), error);
    if (error) {
        throw StateError(kConfigIoErrorCode, "Unable to create the config directory.", true);
    }

    PendingTempFile temp(config_path_.wstring() + L".tmp." + std::filesystem::path(detail::generate_uuid()).wstring());
    UniqueHandle output(CreateFileW(
        temp.path().c_str(),
        GENERIC_WRITE,
        0,
        nullptr,
        CREATE_NEW,
        FILE_ATTRIBUTE_TEMPORARY,
        nullptr));
    if (output.get() == INVALID_HANDLE_VALUE) {
        throw StateError(kConfigIoErrorCode, "Unable to create the temporary config file.", true);
    }

    std::size_t written = 0;
    while (written < text.size()) {
        DWORD count = 0;
        const auto remaining = static_cast<DWORD>(text.size() - written);
        if (!WriteFile(output.get(), text.data() + written, remaining, &count, nullptr) || count == 0) {
            throw StateError(kConfigIoErrorCode, "Unable to write the temporary config file.", true);
        }
        written += count;
    }
    if (!FlushFileBuffers(output.get()) || !output.close()) {
        throw StateError(kConfigIoErrorCode, "Unable to flush the temporary config file.", true);
    }

    if (!MoveFileExW(
            temp.path().c_str(),
            config_path_.c_str(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        throw StateError(kConfigIoErrorCode, "Unable to atomically replace the persisted config.", true);
    }
    temp.committed();
}

} // namespace vibris::mcp