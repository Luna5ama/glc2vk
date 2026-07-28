#include "workspace_binding.hpp"

#include "state_error.hpp"

#include <system_error>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

fs::path canonical_directory(const fs::path& path) {
    std::error_code error;
    const auto canonical = fs::canonical(path, error);
    if (error || !fs::is_directory(canonical, error) || error) {
        throw StateError(kInvalidWorktreeCode, "Workspace root must be an existing directory.");
    }
    return canonical;
}

bool has_git_marker(const fs::path& directory) {
    std::error_code error;
    const auto status = fs::status(directory / ".git", error);
    return !error && (fs::is_directory(status) || fs::is_regular_file(status));
}

} // namespace

WorkspaceBinding resolve_workspace(std::optional<std::filesystem::path> workspace_root) {
    fs::path root;
    if (workspace_root) {
        root = canonical_directory(*workspace_root);
        if (!has_git_marker(root)) {
            throw StateError(kInvalidWorktreeCode, "Explicit workspace root is not a Git worktree.");
        }
    } else {
        std::error_code error;
        const auto current_path = fs::current_path(error);
        if (error) {
            throw StateError(kInvalidWorktreeCode, "Current directory is unavailable.");
        }
        root = canonical_directory(current_path);
        while (!has_git_marker(root)) {
            const auto parent = root.parent_path();
            if (parent == root) {
                throw StateError(kInvalidWorktreeCode, "No Git worktree contains the current directory.");
            }
            root = parent;
        }
    }
    return {root, root / ".codex" / "vibris-session.json"};
}

} // namespace vibris::mcp