#include "commit_extractor.hpp"

#include "state_error.hpp"

#include <archive.h>
#include <archive_entry.h>

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <algorithm>
#include <memory>
#include <span>
#include <string>
#include <utility>

namespace vibris::mcp {
namespace {

constexpr std::size_t kReadBytes = 1024 * 1024;

[[noreturn]] void source_error(std::string_view code, std::string message, bool retryable = false) {
    throw StateError(code, std::move(message), retryable);
}

class OutputFile final {
public:
    explicit OutputFile(const std::filesystem::path& path)
        : handle_(CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr)) {
        if (handle_ == INVALID_HANDLE_VALUE) {
            source_error("INTERNAL_ERROR", "Unable to create an extracted shader file.", true);
        }
        if (GetFileType(handle_) != FILE_TYPE_DISK) {
            CloseHandle(handle_);
            handle_ = INVALID_HANDLE_VALUE;
            source_error("SOURCE_CONTAINS_REPARSE_POINT", "Extracted shader output is not a disk file.");
        }
    }

    OutputFile(const OutputFile&) = delete;
    OutputFile& operator=(const OutputFile&) = delete;

    ~OutputFile() {
        CloseHandle(handle_);
    }

    void write(std::span<const std::byte> data) {
        DWORD written = 0;
        if (!WriteFile(handle_, data.data(), static_cast<DWORD>(data.size()), &written, nullptr) ||
            written != data.size()) {
            source_error("INTERNAL_ERROR", "Unable to write an extracted shader file.", true);
        }
    }

private:
    HANDLE handle_;
};

struct ArchiveReader final {
    explicit ArchiveReader(GitArchivePipe& value)
        : pipe(value), buffer(std::make_unique<std::byte[]>(kReadBytes)) {
    }

    GitArchivePipe& pipe;
    std::unique_ptr<std::byte[]> buffer;
    ArchiveExtractionStats stats{};
    std::string error;
};

la_ssize_t read_archive(archive* reader, void* client_data, const void** output) noexcept {
    auto& context = *static_cast<ArchiveReader*>(client_data);
    try {
        const auto read = context.pipe.read({context.buffer.get(), kReadBytes});
        *output = context.buffer.get();
        context.stats.archive_bytes_read += read;
        context.stats.largest_read_bytes = (std::max)(context.stats.largest_read_bytes, read);
        return static_cast<la_ssize_t>(read);
    } catch (const std::exception& exception) {
        context.error = exception.what();
        archive_set_error(reader, EIO, "%s", context.error.c_str());
        return ARCHIVE_FATAL;
    }
}

using ArchiveHandle = std::unique_ptr<archive, decltype(&archive_read_free)>;

ArchiveHandle open_archive(ArchiveReader& context) {
    ArchiveHandle reader(archive_read_new(), &archive_read_free);
    if (reader == nullptr || archive_read_support_format_tar(reader.get()) != ARCHIVE_OK ||
        archive_read_open(reader.get(), &context, nullptr, read_archive, nullptr) != ARCHIVE_OK) {
        source_error("INTERNAL_ERROR", "Unable to open the streamed Git archive.", true);
    }
    return reader;
}

SourceEntryKind entry_kind(archive_entry* entry) {
    if (archive_entry_hardlink(entry) != nullptr) {
        return SourceEntryKind::hardlink;
    }
    switch (archive_entry_filetype(entry)) {
    case AE_IFREG:
        return SourceEntryKind::regular_file;
    case AE_IFDIR:
        return SourceEntryKind::directory;
    case AE_IFLNK:
        return SourceEntryKind::symlink;
    default:
        return SourceEntryKind::other;
    }
}

void create_directory(
    const SourcePathPolicy& policy,
    const std::filesystem::path& staging,
    const std::filesystem::path& destination) {
    policy.require_no_reparse_ancestry(staging, destination);
    std::error_code error;
    std::filesystem::create_directories(destination, error);
    if (error || !std::filesystem::is_directory(destination, error) || error) {
        source_error("INTERNAL_ERROR", "Unable to create an extracted shader directory.", true);
    }
    policy.require_no_reparse_ancestry(staging, destination);
}

std::filesystem::path destination_for(
    const SourcePathPolicy& policy,
    const std::filesystem::path& staging,
    archive_entry* entry) {
    const char* path = archive_entry_pathname_utf8(entry);
    if (path == nullptr) {
        source_error("SOURCE_CONTAINS_REPARSE_POINT", "Archive entry has no valid UTF-8 path.");
    }
    SourceEntry source_entry{path, entry_kind(entry), false};
    const auto relative = policy.archive_relative_path(source_entry);
    return relative.empty() ? std::filesystem::path{} : staging / relative;
}

void require_limit(bool condition, std::string_view code, std::string message) {
    if (!condition) {
        source_error(code, std::move(message));
    }
}

} // namespace

CommitExtractor::CommitExtractor(SourcePathPolicy path_policy, SourceLimits limits)
    : path_policy_(std::move(path_policy)), limits_(limits) {
}

ArchiveExtractionStats CommitExtractor::extract(
    GitArchivePipe archive,
    const std::filesystem::path& staging) const {
    ArchiveReader context(archive);
    ArchiveHandle reader(nullptr, &archive_read_free);
    try {
        reader = open_archive(context);
    } catch (const StateError&) {
        if (archive.wait() != 0) {
            source_error("COMMIT_HAS_NO_SHADERS", "The requested commit has no shaders directory.");
        }
        throw;
    }
    archive_entry* entry = nullptr;
    auto output_buffer = std::make_unique<std::byte[]>(kReadBytes);

    int result = ARCHIVE_OK;
    while ((result = archive_read_next_header(reader.get(), &entry)) == ARCHIVE_OK) {
        const auto kind = entry_kind(entry);
        const auto destination = destination_for(path_policy_, staging, entry);
        if (destination.empty()) {
            continue;
        }
        if (kind == SourceEntryKind::directory) {
            create_directory(path_policy_, staging, destination);
            continue;
        }
        require_limit(context.stats.extracted_file_count < limits_.max_files, "SOURCE_TOO_MANY_FILES",
            "Commit source contains too many files.");
        const auto declared_size = archive_entry_size(entry);
        require_limit(declared_size >= 0 && static_cast<std::uint64_t>(declared_size) <=
                limits_.max_total_bytes -
                    (std::min)(context.stats.extracted_total_bytes, limits_.max_total_bytes),
            "SOURCE_TOO_LARGE", "Commit source exceeds the total byte limit.");
        create_directory(path_policy_, staging, destination.parent_path());
        path_policy_.require_no_reparse_ancestry(staging, destination);
        OutputFile output(destination);
        std::uint64_t file_bytes = 0;
        for (la_ssize_t read = archive_read_data(reader.get(), output_buffer.get(), kReadBytes); read != 0;
             read = archive_read_data(reader.get(), output_buffer.get(), kReadBytes)) {
            if (read < 0) {
                source_error("INTERNAL_ERROR", "Unable to read an archived shader file.", true);
            }
            file_bytes += static_cast<std::uint64_t>(read);
            require_limit(file_bytes <= static_cast<std::uint64_t>(declared_size) &&
                    file_bytes <= limits_.max_total_bytes -
                        (std::min)(context.stats.extracted_total_bytes, limits_.max_total_bytes),
                "SOURCE_TOO_LARGE", "Commit source exceeds the total byte limit.");
            output.write({output_buffer.get(), static_cast<std::size_t>(read)});
        }
        if (file_bytes != static_cast<std::uint64_t>(declared_size)) {
            source_error("INTERNAL_ERROR", "Archived shader file size did not match its header.", true);
        }
        context.stats.extracted_total_bytes += file_bytes;
        ++context.stats.extracted_file_count;
    }

    const auto git_exit = archive.wait();
    if (git_exit != 0) {
        source_error("COMMIT_HAS_NO_SHADERS", "The requested commit has no shaders directory.");
    }
    if (result != ARCHIVE_EOF) {
        source_error("INTERNAL_ERROR", "Git produced an invalid shader archive.", true);
    }
    if (context.stats.extracted_file_count == 0) {
        source_error("COMMIT_HAS_NO_SHADERS", "The requested commit contains no shader files.");
    }
    return context.stats;
}

} // namespace vibris::mcp