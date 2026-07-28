#include "workspace_binding.hpp"

#include <chrono>
#include <filesystem>
#include <iostream>
#include <stdexcept>

namespace fs = std::filesystem;
using vibris::mcp::resolve_workspace;

namespace {

class TempDirectory final {
public:
    TempDirectory()
        : path_(fs::temp_directory_path() /
              ("vibris-worktree-discovery-" +
                  std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_ / ".git");
    }

    ~TempDirectory() {
        std::error_code ignored;
        fs::remove_all(path_, ignored);
    }

    [[nodiscard]] const fs::path& path() const noexcept {
        return path_;
    }

private:
    fs::path path_;
};

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    try {
        // Given: a nested directory within an existing Git worktree.
        TempDirectory temp;
        const auto nested = temp.path() / "shaders" / "lib";
        fs::create_directories(nested);
        const auto original = fs::current_path();
        fs::current_path(nested);

        // When: the stable Phase-1 worktree discovery seam resolves the implicit root.
        const auto binding = resolve_workspace();
        fs::current_path(original);

        // Then: source preparation will receive the canonical worktree and fixed config path.
        require(fs::equivalent(binding.root, temp.path()), "Resolved the wrong Git worktree root.");
        require(binding.config_path == binding.root / ".codex" / "vibris-session.json",
            "Resolved the wrong worktree config path.");
        std::cout << "PASS WorktreeDiscovery\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL WorktreeDiscovery: " << error.what() << '\n';
        return 1;
    }
}