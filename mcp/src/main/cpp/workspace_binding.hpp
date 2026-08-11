#pragma once

#include <filesystem>

namespace vibris::mcp {

struct WorkspaceBinding final {
    std::filesystem::path root;
    std::filesystem::path identity_path;

    bool operator==(const WorkspaceBinding&) const = default;
};

[[nodiscard]] WorkspaceBinding resolve_workspace(const std::filesystem::path& requested_root);

} // namespace vibris::mcp
