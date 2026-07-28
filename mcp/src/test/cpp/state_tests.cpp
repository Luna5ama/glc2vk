#include "config_store.hpp"
#include "state_error.hpp"
#include "workspace_binding.hpp"
#include "worktree_lock.hpp"

#include <array>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <iterator>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <utility>

namespace fs = std::filesystem;
using vibris::mcp::ConfigStore;
using vibris::mcp::SessionConfig;
using vibris::mcp::StateError;
using vibris::mcp::WorktreeLock;
using vibris::mcp::kMaxConfigJsonBytes;
using vibris::mcp::resolve_workspace;

namespace {

class TempDirectory final {
public:
    explicit TempDirectory(std::string_view label)
        : path_(fs::temp_directory_path() /
            ("vibris-state-" + std::string(label) + "-" +
                std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_);
    }

    TempDirectory(const TempDirectory&) = delete;
    TempDirectory& operator=(const TempDirectory&) = delete;

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

class CurrentDirectory final {
public:
    explicit CurrentDirectory(const fs::path& path) : original_(fs::current_path()) {
        fs::current_path(path);
    }

    CurrentDirectory(const CurrentDirectory&) = delete;
    CurrentDirectory& operator=(const CurrentDirectory&) = delete;

    ~CurrentDirectory() {
        fs::current_path(original_);
    }

private:
    fs::path original_;
};

struct ErrorSnapshot final {
    std::string code;
    bool retryable;
    std::size_t message_size;
};

void require(bool condition, std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

template <typename Action>
ErrorSnapshot capture_state_error(Action&& action) {
    try {
        action();
    } catch (const StateError& error) {
        return {std::string(error.code()), error.retryable(), std::string_view(error.what()).size()};
    }
    throw std::runtime_error("Expected StateError, but the operation succeeded.");
}

SessionConfig valid_config() {
    return {
        1,
        "11111111-1111-4111-8111-111111111111",
        "shaders",
        "shader-test-world",
        "minecraft:overworld",
        "sunset",
        "village-rooftop",
        70.0,
        32,
    };
}

std::string read_bytes(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Expected persisted config file to exist.");
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

void config_valid_atomic_round_trip() {
    // Given: an unconfigured worktree-local config store and one fully populated config.
    TempDirectory temp("config-roundtrip");
    const auto config_path = temp.path() / ".codex" / "vibris-session.json";
    ConfigStore store(config_path);
    const auto expected = valid_config();

    // When: the config is persisted and loaded through the public store.
    store.save(expected);
    const auto actual = store.load();

    // Then: the same typed config is returned from the fixed worktree path.
    require(actual.has_value(), "A saved config must load as configured.");
    require(*actual == expected, "A valid atomic config round trip changed fields.");
    require(fs::is_regular_file(config_path), "ConfigStore wrote outside .codex/vibris-session.json.");

    auto replacement = expected;
    replacement.workspace_id.clear();
    replacement.save_id = "replacement-world";
    store.save(replacement);
    const auto replaced = store.load();
    require(replaced && replaced->workspace_id == expected.workspace_id, "Replacement changed workspace identity.");
    require(replaced && replaced->save_id == replacement.save_id, "Replacement did not atomically become visible.");
}

void same_worktree_mutex() {
    // Given: discovery starts below a Git worktree and its first owner holds the canonical-root lock.
    TempDirectory temp("worktree-lock");
    fs::create_directories(temp.path() / ".git");
    const auto nested = temp.path() / "shaders" / "lib";
    fs::create_directories(nested);
    CurrentDirectory current_directory(nested);
    static_cast<void>(current_directory);
    const auto binding = resolve_workspace(std::nullopt);
    require(fs::equivalent(binding.root, temp.path()), "Worktree discovery did not return the canonical root.");

    ErrorSnapshot duplicate_error {};
    {
        auto first = WorktreeLock::acquire(binding.root);
        static_cast<void>(first);

        // When: another thread attempts to acquire the same named mutex.
        std::promise<ErrorSnapshot> duplicate_result;
        auto future = duplicate_result.get_future();
        std::jthread contender([root = binding.root, &duplicate_result] {
            duplicate_result.set_value(capture_state_error([&root] {
                auto duplicate = WorktreeLock::acquire(root);
                static_cast<void>(duplicate);
            }));
        });
        static_cast<void>(contender);
        duplicate_error = future.get();
    }

    // Then: duplicate ownership is structured, and releasing the first owner permits reacquisition.
    require(duplicate_error.code == "WORKTREE_ALREADY_OWNED", "Duplicate lock returned the wrong code.");
    require(!duplicate_error.retryable, "Duplicate worktree ownership must not be retryable in-process.");
    require(duplicate_error.message_size <= 512, "Duplicate lock error was not bounded.");
    auto reacquired = WorktreeLock::acquire(binding.root);
    static_cast<void>(reacquired);
    TempDirectory other("other-worktree");
    [[maybe_unused]] auto independent = WorktreeLock::acquire(other.path());
}

void malformed_config_preserves_last_good() {
    // Given: a valid persisted config and its exact bytes.
    TempDirectory temp("config-malformed");
    const auto config_path = temp.path() / ".codex" / "vibris-session.json";
    ConfigStore store(config_path);
    store.save(valid_config());
    const auto last_good = read_bytes(config_path);

    // When: malformed JSON is offered to the store boundary.
    const auto error = capture_state_error([&store] {
        static_cast<void>(store.save_json(R"({"schema_version":1,)"));
    });

    // Then: the error is structured and the last-good file is byte-identical.
    require(error.code == "INVALID_CONFIG", "Malformed config returned the wrong code.");
    require(!error.retryable, "Malformed config must not be retryable.");
    require(error.message_size <= 512, "Malformed config error was not bounded.");
    require(read_bytes(config_path) == last_good, "Malformed input changed the last-good config bytes.");
}

void oversize_json_rejected() {
    // Given: a last-good config and syntactically valid JSON beyond the fixed input bound.
    TempDirectory temp("config-oversize");
    const auto config_path = temp.path() / ".codex" / "vibris-session.json";
    ConfigStore store(config_path);
    store.save(valid_config());
    const auto last_good = read_bytes(config_path);
    std::string oversized = R"({"padding":")";
    oversized.append(kMaxConfigJsonBytes, 'x');
    oversized += R"("})";

    // When: the oversized document reaches the config boundary.
    const auto error = capture_state_error([&store, &oversized] {
        static_cast<void>(store.save_json(oversized));
    });

    // Then: size wins over parsing, the response is bounded, and persistence is untouched.
    require(error.code == "REQUEST_TOO_LARGE", "Oversized config returned the wrong code.");
    require(!error.retryable, "Oversized config must not be retryable.");
    require(error.message_size <= 512, "Oversized config error was not bounded.");
    require(read_bytes(config_path) == last_good, "Oversized input changed the last-good config bytes.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 4> test_cases {{
    {"ConfigValidAtomicRoundTrip", config_valid_atomic_round_trip},
    {"SameWorktreeMutex", same_worktree_mutex},
    {"MalformedConfigPreservesLastGood", malformed_config_preserves_last_good},
    {"OversizeJsonRejected", oversize_json_rejected},
}};

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-state-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown state test scenario: " << argv[1] << '\n';
    return 2;
}