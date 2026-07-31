#include "state_error.hpp"
#include "workspace_binding.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <system_error>
#include <type_traits>
#include <utility>

namespace fs = std::filesystem;
using vibris::mcp::StateError;
using vibris::mcp::WorkspaceBinding;
using vibris::mcp::kInvalidWorktreeCode;
using vibris::mcp::resolve_workspace;

namespace {

enum class GitMarker {
    none,
    directory,
    file,
};

class TempDirectory final {
public:
    explicit TempDirectory(const GitMarker marker = GitMarker::none)
        : path_(create_unique_directory()) {
        if (marker == GitMarker::directory) {
            fs::create_directory(path_ / ".git");
        } else if (marker == GitMarker::file) {
            std::ofstream(path_ / ".git") << "gitdir: ../linked-git-dir\n";
        }
    }

    ~TempDirectory() {
        std::error_code ignored;
        fs::remove_all(path_, ignored);
    }

    TempDirectory(const TempDirectory&) = delete;
    TempDirectory& operator=(const TempDirectory&) = delete;

    [[nodiscard]] const fs::path& path() const noexcept {
        return path_;
    }

private:
    static fs::path create_unique_directory() {
        for (int attempt = 0; attempt < 100; ++attempt) {
            const auto suffix = std::to_string(
                std::chrono::steady_clock::now().time_since_epoch().count()) + "-" +
                std::to_string(attempt);
            auto candidate = fs::temp_directory_path() / ("vibris-worktree-discovery-" + suffix);
            std::error_code error;
            if (fs::create_directory(candidate, error)) return candidate;
            if (error && error != std::errc::file_exists) {
                throw std::runtime_error("Could not create an isolated temporary directory.");
            }
        }
        throw std::runtime_error("Could not allocate a unique temporary directory.");
    }

    fs::path path_;
};

class CurrentDirectoryGuard final {
public:
    CurrentDirectoryGuard()
        : original_(fs::current_path()) {
    }

    ~CurrentDirectoryGuard() {
        std::error_code ignored;
        fs::current_path(original_, ignored);
    }

    void set(const fs::path& path) const {
        fs::current_path(path);
    }

private:
    fs::path original_;
};

void require(const bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void require_path_equal(const fs::path& actual, const fs::path& expected, const char* scenario) {
    require(actual == fs::canonical(expected), std::string(scenario) + ": wrong canonical path.");
}

void require_identity_paths(const WorkspaceBinding& binding, const char* scenario) {
    require(binding.identity_path == binding.root / ".codex" / "vibris-workspace.json",
        std::string(scenario) + ": wrong workspace identity path.");
    require(binding.legacy_config_path == binding.root / ".codex" / "vibris-session.json",
        std::string(scenario) + ": wrong legacy config path.");
}

template <typename Callable>
void require_invalid_worktree(Callable&& callable, const char* scenario) {
    try {
        std::invoke(std::forward<Callable>(callable));
    } catch (const StateError& error) {
        require(error.code() == kInvalidWorktreeCode,
            std::string(scenario) + ": wrong structured error code.");
        return;
    }
    throw std::runtime_error(std::string(scenario) + ": expected INVALID_WORKTREE.");
}

bool has_git_ancestor(fs::path path) {
    for (;;) {
        std::error_code error;
        if (fs::exists(path / ".git", error) && !error) return true;
        const auto parent = path.parent_path();
        if (parent == path) return false;
        path = parent;
    }
}

void nested_cwd_discovers_dirty_worktree() {
    TempDirectory worktree(GitMarker::directory);
    const auto nested = worktree.path() / "shaders" / "lib";
    fs::create_directories(nested);
    std::ofstream(worktree.path() / "untracked-dirty-file.glsl") << "// dirty\n";

    CurrentDirectoryGuard cwd;
    cwd.set(nested);
    const auto binding = resolve_workspace();

    require_path_equal(binding.root, worktree.path(), "NestedCwdDirtyWorktree");
    require_identity_paths(binding, "NestedCwdDirtyWorktree");
}

void linked_worktree_git_file_is_accepted() {
    TempDirectory linked_worktree(GitMarker::file);
    const auto nested = linked_worktree.path() / "shaders";
    fs::create_directory(nested);

    CurrentDirectoryGuard cwd;
    cwd.set(nested);
    const auto binding = resolve_workspace();

    require_path_equal(binding.root, linked_worktree.path(), "LinkedWorktreeGitFile");
    require_identity_paths(binding, "LinkedWorktreeGitFile");
}

void independent_repositories_remain_distinct() {
    TempDirectory first(GitMarker::directory);
    TempDirectory second(GitMarker::directory);
    const auto first_nested = first.path() / "shaders";
    const auto second_nested = second.path() / "shaders";
    fs::create_directory(first_nested);
    fs::create_directory(second_nested);

    CurrentDirectoryGuard cwd;
    cwd.set(first_nested);
    const auto first_binding = resolve_workspace();
    cwd.set(second_nested);
    const auto second_binding = resolve_workspace();

    require_path_equal(first_binding.root, first.path(), "IndependentRepositoryFirst");
    require_path_equal(second_binding.root, second.path(), "IndependentRepositorySecond");
    require_identity_paths(first_binding, "IndependentRepositoryFirst");
    require_identity_paths(second_binding, "IndependentRepositorySecond");
    require(first_binding.root != second_binding.root,
        "IndependentRepositories: distinct repositories collapsed to one root.");
}

void explicit_root_is_canonical_and_overrides_cwd() {
    TempDirectory cwd_worktree(GitMarker::directory);
    TempDirectory explicit_worktree(GitMarker::directory);
    fs::create_directories(cwd_worktree.path() / "nested");
    fs::create_directories(explicit_worktree.path() / "canonical-child");
    const auto noncanonical_explicit = explicit_worktree.path() / "canonical-child" / "..";

    CurrentDirectoryGuard cwd;
    cwd.set(cwd_worktree.path() / "nested");
    const auto binding = resolve_workspace(noncanonical_explicit);

    require_path_equal(binding.root, explicit_worktree.path(), "ExplicitOverrideCanonical");
    require_identity_paths(binding, "ExplicitOverrideCanonical");
    require(binding.root != fs::canonical(cwd_worktree.path()),
        "ExplicitOverridePrecedence: cwd incorrectly won over the explicit root.");
}

void malformed_roots_are_rejected() {
    TempDirectory ordinary_directory;
    require_invalid_worktree(
        [&] { static_cast<void>(resolve_workspace(ordinary_directory.path())); },
        "ExplicitOrdinaryDirectory");
    require_invalid_worktree(
        [&] { static_cast<void>(resolve_workspace(ordinary_directory.path() / "missing")); },
        "ExplicitNonexistentDirectory");
}

void cwd_outside_any_worktree_is_rejected() {
    TempDirectory outside;
    require(!has_git_ancestor(outside.path()),
        "OutsideWorktreeFixture: temporary directory unexpectedly has a Git ancestor.");

    CurrentDirectoryGuard cwd;
    cwd.set(outside.path());
    require_invalid_worktree([] { static_cast<void>(resolve_workspace()); }, "CwdOutsideWorktree");
}

} // namespace

int main() {
    static_assert(std::is_same_v<decltype(&resolve_workspace),
        WorkspaceBinding (*)(std::optional<fs::path>)>,
        "Workspace routing must remain one immutable process-start root or explicit override.");

    try {
        nested_cwd_discovers_dirty_worktree();
        linked_worktree_git_file_is_accepted();
        independent_repositories_remain_distinct();
        explicit_root_is_canonical_and_overrides_cwd();
        malformed_roots_are_rejected();
        cwd_outside_any_worktree_is_rejected();
        std::cout << "PASS WorktreeDiscovery\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL WorktreeDiscovery: " << error.what() << '\n';
        return 1;
    }
}
