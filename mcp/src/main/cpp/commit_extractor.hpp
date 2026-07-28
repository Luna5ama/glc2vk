#pragma once

#include "git_repository.hpp"
#include "source_path_policy.hpp"
#include "source_types.hpp"

#include <cstddef>
#include <cstdint>
#include <filesystem>

namespace vibris::mcp {

struct ArchiveExtractionStats final {
    std::uint64_t archive_bytes_read;
    std::size_t largest_read_bytes;
    std::uint32_t extracted_file_count;
    std::uint64_t extracted_total_bytes;
};

class CommitExtractor final {
public:
    CommitExtractor(SourcePathPolicy path_policy, SourceLimits limits);

    [[nodiscard]] ArchiveExtractionStats extract(
        GitArchivePipe archive,
        const std::filesystem::path& staging) const;

private:
    SourcePathPolicy path_policy_;
    SourceLimits limits_;
};

} // namespace vibris::mcp