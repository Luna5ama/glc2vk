#pragma once

#include <filesystem>
#include <string>

namespace vibris::mcp {

enum class SourceEntryKind {
    regular_file,
    directory,
    symlink,
    hardlink,
    other,
};

struct SourceEntry final {
    std::string path;
    SourceEntryKind kind;
    bool has_reparse_point;
};

class SourcePathPolicy final {
  public:
    [[nodiscard]] std::filesystem::path archive_relative_path(const SourceEntry& entry) const;

    void require_no_reparse_ancestry(const std::filesystem::path& root, const std::filesystem::path& candidate) const;
};

} // namespace vibris::mcp