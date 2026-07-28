#include "workspace_copier.hpp"

#include "state_error.hpp"

#define NOMINMAX
#include <Windows.h>

#include <algorithm>
#include <fstream>
#include <limits>
#include <string>
#include <string_view>
#include <utility>

namespace vibris::mcp {

namespace {

namespace fs = std::filesystem;

constexpr std::string_view kChangedCode = "SOURCE_CHANGED_DURING_SNAPSHOT";
constexpr std::string_view kInternalErrorCode = "INTERNAL_ERROR";
constexpr std::string_view kMissingShadersCode = "SHADERS_DIRECTORY_MISSING";
constexpr std::string_view kReparseCode = "SOURCE_CONTAINS_REPARSE_POINT";
constexpr std::string_view kTooLargeCode = "SOURCE_TOO_LARGE";
constexpr std::string_view kTooManyFilesCode = "SOURCE_TOO_MANY_FILES";
constexpr std::size_t kCopyBufferBytes = 1024 * 1024;

[[noreturn]] void throw_error(std::string_view code, std::string message, bool retryable = false) {
    throw StateError(code, std::move(message), retryable);
}

[[nodiscard]] DWORD attributes_of(const fs::path& path, bool root) {
    const DWORD attributes = GetFileAttributesW(path.c_str());
    if (attributes != INVALID_FILE_ATTRIBUTES) {
        return attributes;
    }
    const DWORD error = GetLastError();
    if (root && (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND)) {
        throw_error(kMissingShadersCode, "The workspace shaders directory is missing.");
    }
    if (root) {
        throw_error(kInternalErrorCode, "Could not inspect the workspace shaders directory.");
    }
    throw_error(kChangedCode, "The workspace source changed while it was being read.", true);
}

void require_ordinary(DWORD attributes) {
    if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        throw_error(kReparseCode, "The workspace source contains a reparse point.");
    }
}

[[nodiscard]] WorkspaceEntryType entry_type(DWORD attributes) {
    if ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
        return WorkspaceEntryType::directory;
    }
    if ((attributes & FILE_ATTRIBUTE_DEVICE) == 0) {
        return WorkspaceEntryType::regular_file;
    }
    throw_error(kReparseCode, "The workspace source contains a non-ordinary entry.");
}

[[noreturn]] void throw_changed() {
    throw_error(kChangedCode, "The workspace source changed while it was being read.", true);
}

class InputFile final {
public:
    explicit InputFile(const fs::path& path)
        : handle_(CreateFileW(path.c_str(), GENERIC_READ,
              FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN, nullptr)) {
        if (handle_ == INVALID_HANDLE_VALUE) {
            throw_changed();
        }
    }

    void require_regular() const {
        FILE_ATTRIBUTE_TAG_INFO information{};
        if (!GetFileInformationByHandleEx(
                handle_, FileAttributeTagInfo, &information, sizeof(information))) {
            throw_changed();
        }
        if (GetFileType(handle_) != FILE_TYPE_DISK ||
            (information.FileAttributes &
                (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_DEVICE)) != 0) {
            throw_error(kReparseCode, "The workspace source contains a non-ordinary entry.");
        }
    }

    InputFile(const InputFile&) = delete;
    InputFile& operator=(const InputFile&) = delete;

    ~InputFile() {
        if (handle_ != INVALID_HANDLE_VALUE) {
            CloseHandle(handle_);
        }
    }

    [[nodiscard]] DWORD read(char* buffer, DWORD size) const {
        DWORD bytes = 0;
        if (!ReadFile(handle_, buffer, size, &bytes, nullptr)) {
            throw_changed();
        }
        return bytes;
    }

private:
    HANDLE handle_;
};

} // namespace

WorkspaceMetadata enumerate_workspace_tree(const fs::path& root, const SourceLimits& limits) {
    const auto root_attributes = attributes_of(root, true);
    require_ordinary(root_attributes);
    if ((root_attributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        throw_error(kMissingShadersCode, "The workspace shaders path is not a directory.");
    }

    WorkspaceMetadata metadata{{}, 0, 0};
    std::error_code error;
    fs::recursive_directory_iterator iterator(root, fs::directory_options::none, error);
    const fs::recursive_directory_iterator end;
    if (error) {
        throw_error(kInternalErrorCode, "Could not enumerate the workspace shaders directory.");
    }
    while (iterator != end) {
        const auto attributes = attributes_of(iterator->path(), false);
        require_ordinary(attributes);
        const auto type = entry_type(attributes);
        std::uint64_t size = 0;
        if (type == WorkspaceEntryType::regular_file) {
            const auto file_size = fs::file_size(iterator->path(), error);
            if (error || file_size > std::numeric_limits<std::uint64_t>::max()) {
                throw_changed();
            }
            size = static_cast<std::uint64_t>(file_size);
            if (metadata.file_count == limits.max_files) {
                throw_error(kTooManyFilesCode, "The workspace source contains too many files.");
            }
            if (size > limits.max_total_bytes - metadata.total_bytes) {
                throw_error(kTooLargeCode, "The workspace source exceeds the byte limit.");
            }
            ++metadata.file_count;
            metadata.total_bytes += size;
        }
        const auto write_time = fs::last_write_time(iterator->path(), error);
        if (error) {
            throw_changed();
        }
        metadata.entries.push_back({iterator->path().lexically_relative(root), type, size, write_time});
        iterator.increment(error);
        if (error) {
            throw_changed();
        }
    }
    std::ranges::sort(metadata.entries, {}, &SourceFileMetadata::relative_path);
    return metadata;
}

void copy_workspace_tree(const fs::path& source, const fs::path& destination) {
    copy_workspace_tree_after_check(source, destination, {});
}

void copy_workspace_tree_after_check(
    const fs::path& source, const fs::path& destination, const WorkspaceFileCheckedHook& file_checked_hook) {
    const auto root_attributes = attributes_of(source, true);
    require_ordinary(root_attributes);
    if ((root_attributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        throw_error(kMissingShadersCode, "The workspace shaders path is not a directory.");
    }

    std::error_code error;
    fs::create_directories(destination, error);
    if (error) {
        throw_error(kInternalErrorCode, "Could not create the prepared workspace directory.");
    }
    std::vector<char> buffer(kCopyBufferBytes);
    fs::recursive_directory_iterator iterator(source, fs::directory_options::none, error);
    const fs::recursive_directory_iterator end;
    if (error) {
        throw_changed();
    }
    while (iterator != end) {
        const auto attributes = attributes_of(iterator->path(), false);
        require_ordinary(attributes);
        const auto type = entry_type(attributes);
        const auto target = destination / iterator->path().lexically_relative(source);
        if (type == WorkspaceEntryType::directory) {
            fs::create_directory(target, error);
            if (error) {
                throw_error(kInternalErrorCode, "Could not create a prepared workspace subdirectory.");
            }
        } else {
            if (file_checked_hook) {
                file_checked_hook(iterator->path());
            }
            InputFile input(iterator->path());
            input.require_regular();
            std::ofstream output(target, std::ios::binary | std::ios::trunc);
            if (!output.good()) {
                throw_error(kInternalErrorCode, "Could not create the prepared workspace source.");
            }
            for (auto bytes = input.read(buffer.data(), static_cast<DWORD>(buffer.size())); bytes != 0;
                 bytes = input.read(buffer.data(), static_cast<DWORD>(buffer.size()))) {
                output.write(buffer.data(), static_cast<std::streamsize>(bytes));
                if (!output.good()) {
                    throw_error(kInternalErrorCode, "Could not write the prepared workspace source.");
                }
            }
            require_ordinary(attributes_of(iterator->path(), false));
        }
        iterator.increment(error);
        if (error) {
            throw_changed();
        }
    }
}

} // namespace vibris::mcp