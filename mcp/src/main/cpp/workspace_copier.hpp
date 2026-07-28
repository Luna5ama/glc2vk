#pragma once

#include "source_types.hpp"

#include <cstdint>
#include <filesystem>
#include <functional>
#include <vector>

namespace vibris::mcp {

enum class WorkspaceEntryType : std::uint8_t {
    regular_file,
    directory,
};

struct SourceFileMetadata final {
    std::filesystem::path relative_path;
    WorkspaceEntryType type;
    std::uint64_t size;
    std::filesystem::file_time_type last_write_time;

    [[nodiscard]] bool operator==(const SourceFileMetadata&) const = default;
};

struct WorkspaceMetadata final {
    std::vector<SourceFileMetadata> entries;
    std::uint64_t total_bytes;
    std::uint32_t file_count;

    [[nodiscard]] bool operator==(const WorkspaceMetadata&) const = default;
};

[[nodiscard]] WorkspaceMetadata enumerate_workspace_tree(
    const std::filesystem::path& root, const SourceLimits& limits);

void copy_workspace_tree(const std::filesystem::path& source, const std::filesystem::path& destination);

using WorkspaceFileCheckedHook = std::function<void(const std::filesystem::path&)>;

void copy_workspace_tree_after_check(const std::filesystem::path& source,
    const std::filesystem::path& destination,
    const WorkspaceFileCheckedHook& file_checked_hook);

} // namespace vibris::mcp