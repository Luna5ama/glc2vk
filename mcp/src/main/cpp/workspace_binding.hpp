#pragma once

#include <filesystem>
#include <optional>

namespace vibris::mcp {

struct WorkspaceBinding final {
    std::filesystem::path root;
    std::filesystem::path identity_path;
    std::filesystem::path legacy_config_path;

    bool operator==(const WorkspaceBinding&) const = default;
};

[[nodiscard]] WorkspaceBinding resolve_workspace(
    std::optional<std::filesystem::path> workspace_root = std::nullopt);

} // namespace vibris::mcp
