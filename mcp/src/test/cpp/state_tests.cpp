#include "backend_state_fixture.hpp"
#include "config_document.hpp"
#include "mcp_backend.hpp"
#include "state_error.hpp"
#include "tool_registry.hpp"
#include "workspace_identity_store.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <array>
#include <atomic>
#include <barrier>
#include <chrono>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_set>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using vibris::mcp::McpBackend;
using vibris::mcp::SessionConfig;
using vibris::mcp::StateError;
using vibris::mcp::WorkspaceIdentityStore;

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

void write_bytes(const fs::path& path, std::string_view bytes) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    require(output.good(), "Unable to create test state file.");
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    require(output.good(), "Unable to write test state file.");
}

const vibris::mcp::Json& require_json(
    const vibris::mcp::ToolOutcome& outcome, std::string_view message) {
    const auto* result = std::get_if<vibris::mcp::Json>(&outcome);
    require(result != nullptr, message);
    return *result;
}

vibris::mcp::Json configure_arguments(
    std::string_view scene, double fov, std::uint32_t warmup_frames) {
    return {
        {"save_id", "save-" + std::string(scene)},
        {"dimension_id", "dimension-" + std::string(scene)},
        {"time_preset_id", "time-" + std::string(scene)},
        {"camera_preset_id", "camera-" + std::string(scene)},
        {"fov", fov},
        {"default_warmup_frames", warmup_frames},
    };
}

using IdentityIoOperation = vibris::mcp::detail::WorkspaceIdentityIoOperation;

class IdentityIoHooks final : public vibris::mcp::detail::WorkspaceIdentityIoHooks {
public:
    enum class Substitution {
        none,
        regular,
        reparse,
    };

    std::barrier<>* move_gate = nullptr;
    std::optional<IdentityIoOperation> forced_operation;
    std::uint32_t forced_error = ERROR_ACCESS_DENIED;
    std::string external_winner;
    bool hold_external_winner = false;
    std::size_t release_winner_after_retries = 0;
    Substitution substitution = Substitution::none;
    fs::path substitution_target;
    std::size_t create_collision_budget = 0;
    std::string collision_sentinel;
    std::vector<fs::path> collision_paths;
    std::atomic<std::size_t> move_winners = 0;
    std::atomic<std::size_t> move_losers = 0;
    std::atomic<std::size_t> cleanup_successes = 0;
    std::atomic<std::size_t> cleanup_failures = 0;
    std::atomic<bool> external_winner_written = false;
    std::atomic<bool> setup_failed = false;
    std::atomic<bool> substitution_attempted = false;
    std::atomic<bool> substitution_succeeded = false;
    std::atomic<std::size_t> create_collisions = 0;
    std::atomic<std::size_t> winner_retries = 0;
    HANDLE held_winner = INVALID_HANDLE_VALUE;

    ~IdentityIoHooks() override {
        if (held_winner != INVALID_HANDLE_VALUE) {
            CloseHandle(held_winner);
        }
    }

    void before(IdentityIoOperation operation, const fs::path& temporary_path,
        const fs::path& identity_path) noexcept override {
        if (operation == IdentityIoOperation::winner_retry) {
            const auto retries = ++winner_retries;
            if (held_winner != INVALID_HANDLE_VALUE &&
                retries >= release_winner_after_retries) {
                CloseHandle(held_winner);
                held_winner = INVALID_HANDLE_VALUE;
            }
            return;
        }
        if (operation == IdentityIoOperation::create &&
            collision_paths.size() < create_collision_budget) {
            try {
                collision_paths.push_back(temporary_path);
                write_bytes(temporary_path, collision_sentinel);
            } catch (...) {
                setup_failed = true;
            }
        }
        if (operation == IdentityIoOperation::move && !external_winner.empty() &&
            !external_winner_written.exchange(true)) {
            try {
                write_bytes(identity_path, external_winner);
                if (hold_external_winner) {
                    held_winner = CreateFileW(identity_path.c_str(), GENERIC_WRITE | DELETE,
                        FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (held_winner == INVALID_HANDLE_VALUE) {
                        setup_failed = true;
                    }
                }
            } catch (...) {
                setup_failed = true;
            }
        }
        if (operation == IdentityIoOperation::move && substitution != Substitution::none &&
            !substitution_attempted.exchange(true)) {
            const auto displaced = temporary_path.wstring() + L".displaced";
            if (MoveFileExW(temporary_path.c_str(), displaced.c_str(), MOVEFILE_REPLACE_EXISTING)) {
                if (substitution == Substitution::regular) {
                    try {
                        write_bytes(temporary_path,
                            vibris::mcp::Json {{"schema_version", 1},
                                {"workspace_id", "33333333-3333-4333-8333-333333333333"}}.dump(2));
                        substitution_succeeded = true;
                    } catch (...) {
                        setup_failed = true;
                    }
                } else {
                    substitution_succeeded = CreateSymbolicLinkW(temporary_path.c_str(),
                        substitution_target.c_str(), 0) != 0;
                    setup_failed = !substitution_succeeded;
                }
            }
        }
        if (operation == IdentityIoOperation::move && move_gate) {
            move_gate->arrive_and_wait();
        }
    }

    std::optional<std::uint32_t> injected_error(
        IdentityIoOperation operation, const fs::path&, const fs::path&) noexcept override {
        if (forced_operation && *forced_operation == operation) {
            return forced_error;
        }
        return std::nullopt;
    }

    void after(IdentityIoOperation operation, const fs::path&, const fs::path&,
        bool success, std::uint32_t error) noexcept override {
        if (operation == IdentityIoOperation::create && !success &&
            (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS)) {
            ++create_collisions;
        }
        if (operation == IdentityIoOperation::move) {
            success ? ++move_winners : ++move_losers;
        }
        if (operation == IdentityIoOperation::cleanup) {
            success ? ++cleanup_successes : ++cleanup_failures;
        }
    }
};

class TestHandle final {
public:
    explicit TestHandle(HANDLE handle) : handle_(handle) {
        require(handle_ != INVALID_HANDLE_VALUE, "Unable to create the locked state-file fixture.");
    }

    TestHandle(const TestHandle&) = delete;
    TestHandle& operator=(const TestHandle&) = delete;

    ~TestHandle() {
        CloseHandle(handle_);
    }

private:
    HANDLE handle_;
};

bool has_identity_temp(const fs::path& directory) {
    std::error_code error;
    if (!fs::is_directory(directory, error) || error) {
        return false;
    }
    for (const auto& entry : fs::directory_iterator(directory)) {
        if (entry.path().filename().string().starts_with("vibris-workspace.json.tmp.")) {
            return true;
        }
    }
    return false;
}

std::string nested_value(std::size_t container_count) {
    std::string value = "0";
    for (std::size_t index = 0; index < container_count; ++index) {
        value = "{\"n\":" + value + "}";
    }
    return value;
}

void workspace_identity_concurrent_first_use() {
    TempDirectory temp("identity-first-use");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    constexpr std::size_t contender_count = 32;
    std::array<std::string, contender_count> identities;
    std::array<std::exception_ptr, contender_count> errors;
    std::barrier start(static_cast<std::ptrdiff_t>(contender_count));
    std::vector<std::jthread> contenders;
    contenders.reserve(contender_count);
    for (std::size_t index = 0; index < contender_count; ++index) {
        contenders.emplace_back([&, index] {
            try {
                WorkspaceIdentityStore store(identity_path, legacy_path);
                start.arrive_and_wait();
                identities[index] = store.load_or_create();
            } catch (...) {
                errors[index] = std::current_exception();
            }
        });
    }
    contenders.clear();

    for (const auto& error : errors) {
        if (error) {
            std::rethrow_exception(error);
        }
    }
    require(vibris::mcp::detail::is_uuid(identities.front()), "First-use identity is not a UUID.");
    for (const auto& identity : identities) {
        require(identity == identities.front(), "First-use contenders observed divergent workspace IDs.");
    }

    const auto document = vibris::mcp::Json::parse(read_bytes(identity_path));
    require(document.is_object() && document.size() == 2, "Workspace identity schema is not strict.");
    require(document.at("schema_version") == 1, "Workspace identity schema version changed.");
    require(document.at("workspace_id") == identities.front(), "Persisted identity differs from the winner.");
    for (const auto& entry : fs::directory_iterator(identity_path.parent_path())) {
        require(!entry.path().filename().string().starts_with("vibris-workspace.json.tmp."),
                "First-use publication left a temporary identity file.");
    }
}

void workspace_identity_deterministic_publication() {
    TempDirectory temp("identity-deterministic-publication");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    std::barrier move_gate(2);
    IdentityIoHooks hooks;
    hooks.move_gate = &move_gate;
    std::array<std::string, 2> identities;
    std::array<std::exception_ptr, 2> errors;
    std::array<std::jthread, 2> contenders {
        std::jthread([&] {
            try {
                identities[0] = WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create();
            } catch (...) {
                errors[0] = std::current_exception();
            }
        }),
        std::jthread([&] {
            try {
                identities[1] = WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create();
            } catch (...) {
                errors[1] = std::current_exception();
            }
        }),
    };
    for (auto& contender : contenders) {
        contender.join();
    }
    for (const auto& error : errors) {
        if (error) {
            std::rethrow_exception(error);
        }
    }

    require(identities[0] == identities[1], "Deterministic contenders observed different workspace IDs.");
    require(hooks.move_winners == 1, "Deterministic publication did not have exactly one move winner.");
    require(hooks.move_losers >= 1, "Deterministic publication did not exercise the loser path.");
    require(hooks.cleanup_successes >= 1 && hooks.cleanup_failures == 0,
        "Deterministic publication did not check loser cleanup.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Deterministic publication left a temporary identity file.");
    require(vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id") == identities[0],
        "Deterministic publication returned an identity other than the published winner.");
}

void workspace_identity_io_failures_cleanup() {
    const std::array failures {
        std::pair {IdentityIoOperation::write, static_cast<std::uint32_t>(ERROR_WRITE_FAULT)},
        std::pair {IdentityIoOperation::flush, static_cast<std::uint32_t>(ERROR_WRITE_FAULT)},
        std::pair {IdentityIoOperation::move, static_cast<std::uint32_t>(ERROR_ACCESS_DENIED)},
    };
    for (const auto& [operation, error_code] : failures) {
        TempDirectory temp("identity-io-failure");
        const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
        const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
        IdentityIoHooks hooks;
        hooks.forced_operation = operation;
        hooks.forced_error = error_code;
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Injected identity I/O failure returned the wrong error contract.");
        require(error.message_size <= 512, "Injected identity I/O error was not bounded.");
        require(!fs::exists(identity_path), "Injected identity I/O failure published an identity.");
        require(hooks.cleanup_successes >= 1 && !has_identity_temp(identity_path.parent_path()),
            "Injected identity I/O failure did not remove its temporary file.");
    }

    TempDirectory cleanup_temp("identity-cleanup-failure");
    const auto cleanup_identity = cleanup_temp.path() / ".codex" / "vibris-workspace.json";
    const auto cleanup_legacy = cleanup_temp.path() / ".codex" / "vibris-session.json";
    const auto winner_id = std::string("22222222-2222-4222-8222-222222222222");
    const auto winner_bytes = vibris::mcp::Json {
        {"schema_version", 1}, {"workspace_id", winner_id}}.dump(2);
    IdentityIoHooks cleanup_hooks;
    cleanup_hooks.external_winner = winner_bytes;
    cleanup_hooks.forced_operation = IdentityIoOperation::cleanup;
    cleanup_hooks.forced_error = ERROR_ACCESS_DENIED;
    const auto cleanup_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(cleanup_identity, cleanup_legacy, cleanup_hooks).load_or_create());
    });
    require(cleanup_error.code == "CONFIG_IO_ERROR" && cleanup_error.retryable,
        "Checked loser-cleanup failure returned the wrong error contract.");
    require(cleanup_hooks.cleanup_failures >= 1, "Checked loser-cleanup failure was not observed.");
    require(read_bytes(cleanup_identity) == winner_bytes, "Cleanup failure changed the published winner.");
    require(!has_identity_temp(cleanup_identity.parent_path()),
        "Cleanup failure fallback left a temporary identity file.");
}

void workspace_identity_malformed_winner() {
    TempDirectory temp("identity-malformed-winner");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    const std::string malformed = R"({"schema_version":1,"workspace_id":"not-a-uuid"})";
    IdentityIoHooks hooks;
    hooks.external_winner = malformed;
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create());
    });
    require(!hooks.setup_failed, "Malformed-winner fixture setup failed.");
    require(error.code == "INVALID_CONFIG" && !error.retryable,
        "Malformed publication winner returned the wrong error contract.");
    require(hooks.move_losers == 1 && hooks.cleanup_successes == 1,
        "Malformed publication winner did not exercise checked loser cleanup.");
    require(read_bytes(identity_path) == malformed, "Malformed publication winner was replaced.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Malformed publication winner left a temporary identity file.");
}

void workspace_identity_retries_only_publication_loser() {
    TempDirectory initial("identity-initial-read-no-retry");
    const auto initial_identity = initial.path() / ".codex" / "vibris-workspace.json";
    const auto initial_legacy = initial.path() / ".codex" / "vibris-session.json";
    static_cast<void>(WorkspaceIdentityStore(initial_identity, initial_legacy).load_or_create());
    IdentityIoHooks initial_hooks;
    {
        TestHandle writer(CreateFileW(initial_identity.c_str(), GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(
                initial_identity, initial_legacy, initial_hooks).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Initial busy identity read returned the wrong error contract.");
    }
    require(initial_hooks.winner_retries == 0,
        "Initial identity read entered the publication-loser retry path.");

    TempDirectory loser("identity-publication-loser-retry");
    const auto loser_identity = loser.path() / ".codex" / "vibris-workspace.json";
    const auto loser_legacy = loser.path() / ".codex" / "vibris-session.json";
    const auto winner_id = std::string("55555555-5555-4555-8555-555555555555");
    const auto winner_bytes = vibris::mcp::Json {
        {"schema_version", 1}, {"workspace_id", winner_id}}.dump(2);
    IdentityIoHooks loser_hooks;
    loser_hooks.external_winner = winner_bytes;
    loser_hooks.hold_external_winner = true;
    loser_hooks.release_winner_after_retries = 3;
    const auto returned = WorkspaceIdentityStore(
        loser_identity, loser_legacy, loser_hooks).load_or_create();
    require(!loser_hooks.setup_failed && loser_hooks.winner_retries == 3,
        "Publication loser did not use the bounded winner-only retry path.");
    require(loser_hooks.held_winner == INVALID_HANDLE_VALUE,
        "Publication-loser fixture did not release the winner handle.");
    require(returned == winner_id && read_bytes(loser_identity) == winner_bytes,
        "Publication-loser retry returned an ID different from the durable winner.");
    require(!has_identity_temp(loser_identity.parent_path()),
        "Publication-loser retry left an owned temporary identity file.");

    TempDirectory exhausted("identity-publication-loser-retry-exhausted");
    const auto exhausted_identity = exhausted.path() / ".codex" / "vibris-workspace.json";
    const auto exhausted_legacy = exhausted.path() / ".codex" / "vibris-session.json";
    IdentityIoHooks exhausted_hooks;
    exhausted_hooks.external_winner = winner_bytes;
    exhausted_hooks.hold_external_winner = true;
    exhausted_hooks.release_winner_after_retries =
        vibris::mcp::detail::kMaxWorkspaceIdentityWinnerReadRetries + 1;
    const auto exhausted_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(
            exhausted_identity, exhausted_legacy, exhausted_hooks).load_or_create());
    });
    require(!exhausted_hooks.setup_failed && exhausted_error.code == "CONFIG_IO_ERROR" &&
            exhausted_error.retryable,
        "Exhausted publication-loser retry returned the wrong error contract.");
    require(exhausted_hooks.winner_retries ==
            vibris::mcp::detail::kMaxWorkspaceIdentityWinnerReadRetries,
        "Publication-loser retry exceeded its exact bounded wait count.");
    require(!has_identity_temp(exhausted_identity.parent_path()),
        "Exhausted publication-loser retry left an owned temporary identity file.");
}

void workspace_identity_blocks_temp_substitution() {
    const std::array substitutions {
        IdentityIoHooks::Substitution::regular,
        IdentityIoHooks::Substitution::reparse,
    };
    for (const auto substitution : substitutions) {
        TempDirectory temp("identity-temp-substitution");
        const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
        const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
        const auto reparse_target = temp.path() / "attacker-identity.json";
        write_bytes(reparse_target,
            vibris::mcp::Json {{"schema_version", 1},
                {"workspace_id", "44444444-4444-4444-8444-444444444444"}}.dump(2));
        IdentityIoHooks hooks;
        hooks.substitution = substitution;
        hooks.substitution_target = reparse_target;
        const auto returned = WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create();
        require(hooks.substitution_attempted, "Temporary-path substitution hook did not run.");
        require(!hooks.substitution_succeeded && !hooks.setup_failed,
            "Attacker replaced the temporary identity pathname while publication owned it.");
        const auto attributes = GetFileAttributesW(identity_path.c_str());
        require(attributes != INVALID_FILE_ATTRIBUTES &&
                (attributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0 &&
                (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0,
            "Published workspace identity is not an ordinary file.");
        const auto persisted = vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id").get<std::string>();
        require(returned == persisted,
            "Publication returned a workspace ID different from the durable file.");
    }
}

void workspace_identity_preserves_create_collision() {
    TempDirectory temp("identity-create-collision");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    IdentityIoHooks hooks;
    hooks.create_collision_budget = 1;
    hooks.collision_sentinel = "attacker-owned-sentinel";
    const auto returned = WorkspaceIdentityStore(identity_path, legacy_path, hooks).load_or_create();
    require(hooks.collision_paths.size() == 1 && hooks.create_collisions == 1 && !hooks.setup_failed,
        "Unique-temp collision fixture did not exercise exactly one retry.");
    require(read_bytes(hooks.collision_paths.front()) == hooks.collision_sentinel,
        "Workspace identity publication deleted or changed an unowned collision entry.");
    require(vibris::mcp::Json::parse(read_bytes(identity_path)).at("workspace_id") == returned,
        "Collision retry returned an ID different from the durable identity.");
    require(DeleteFileW(hooks.collision_paths.front().c_str()) != 0,
        "Unable to remove the test-owned collision sentinel.");
    require(!has_identity_temp(identity_path.parent_path()),
        "Collision retry left an owned workspace identity temporary file.");

    TempDirectory exhausted("identity-create-collision-exhausted");
    const auto exhausted_identity = exhausted.path() / ".codex" / "vibris-workspace.json";
    const auto exhausted_legacy = exhausted.path() / ".codex" / "vibris-session.json";
    IdentityIoHooks exhausted_hooks;
    exhausted_hooks.create_collision_budget =
        vibris::mcp::detail::kMaxWorkspaceIdentityTempCreateAttempts;
    exhausted_hooks.collision_sentinel = "bounded-attacker-owned-sentinel";
    const auto exhausted_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(
            exhausted_identity, exhausted_legacy, exhausted_hooks).load_or_create());
    });
    require(exhausted_error.code == "CONFIG_IO_ERROR" && exhausted_error.retryable,
        "Exhausted unique-temp collisions returned the wrong error contract.");
    require(exhausted_hooks.collision_paths.size() ==
            vibris::mcp::detail::kMaxWorkspaceIdentityTempCreateAttempts &&
            exhausted_hooks.create_collisions == exhausted_hooks.collision_paths.size() &&
            !exhausted_hooks.setup_failed,
        "Unique-temp collision retries were not bounded at the explicit limit.");
    std::unordered_set<std::wstring> collision_names;
    for (const auto& path : exhausted_hooks.collision_paths) {
        collision_names.insert(path.filename().wstring());
        require(read_bytes(path) == exhausted_hooks.collision_sentinel,
            "Exhausted collision retry changed an unowned sentinel.");
        require(DeleteFileW(path.c_str()) != 0,
            "Unable to remove an exhausted test-owned collision sentinel.");
    }
    require(collision_names.size() == exhausted_hooks.collision_paths.size(),
        "Unique-temp collision retry reused a candidate name.");
    require(!fs::exists(exhausted_identity) && !has_identity_temp(exhausted_identity.parent_path()),
        "Exhausted collision retry published an identity or left an owned temp.");
}

void workspace_identity_legacy_migration() {
    TempDirectory migrated("identity-legacy-valid");
    const auto migrated_identity = migrated.path() / ".codex" / "vibris-workspace.json";
    const auto migrated_legacy = migrated.path() / ".codex" / "vibris-session.json";
    const auto legacy_bytes = vibris::mcp::detail::serialize_config(valid_config());
    write_bytes(migrated_legacy, legacy_bytes);
    const auto adopted = WorkspaceIdentityStore(migrated_identity, migrated_legacy).load_or_create();
    require(adopted == valid_config().workspace_id, "Valid legacy workspace ID was not adopted.");
    require(read_bytes(migrated_legacy) == legacy_bytes, "Legacy migration rewrote the session file.");

    TempDirectory invalid_legacy("identity-legacy-invalid");
    const auto generated_identity = invalid_legacy.path() / ".codex" / "vibris-workspace.json";
    const auto invalid_legacy_path = invalid_legacy.path() / ".codex" / "vibris-session.json";
    const std::string invalid_legacy_bytes = R"({"schema_version":1,"workspace_id":"not-a-uuid"})";
    write_bytes(invalid_legacy_path, invalid_legacy_bytes);
    const auto generated = WorkspaceIdentityStore(generated_identity, invalid_legacy_path).load_or_create();
    require(vibris::mcp::detail::is_uuid(generated), "Invalid legacy session did not produce a new UUID.");
    require(read_bytes(invalid_legacy_path) == invalid_legacy_bytes, "Invalid legacy session was rewritten.");

    TempDirectory malformed("identity-malformed");
    const auto malformed_identity = malformed.path() / ".codex" / "vibris-workspace.json";
    const auto missing_legacy = malformed.path() / ".codex" / "vibris-session.json";
    const std::string malformed_bytes = R"({"schema_version":1,"workspace_id":"not-a-uuid","extra":true})";
    write_bytes(malformed_identity, malformed_bytes);
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(malformed_identity, missing_legacy).load_or_create());
    });
    require(error.code == "INVALID_CONFIG", "Malformed identity returned the wrong code.");
    require(!error.retryable, "Malformed identity must not be retryable.");
    require(error.message_size <= 512, "Malformed identity error was not bounded.");
    require(read_bytes(malformed_identity) == malformed_bytes, "Malformed identity was replaced.");
}

void workspace_identity_rejects_duplicate_keys() {
    TempDirectory temp("identity-duplicate-key");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    write_bytes(identity_path,
        R"({"schema_version":1,"workspace_id":"11111111-1111-4111-8111-111111111111","workspace_id":"22222222-2222-4222-8222-222222222222"})");
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
    });
    require(error.code == "INVALID_CONFIG", "Duplicate identity keys were accepted.");

    TempDirectory legacy_temp("legacy-duplicate-key");
    const auto generated_identity = legacy_temp.path() / ".codex" / "vibris-workspace.json";
    const auto duplicate_legacy = legacy_temp.path() / ".codex" / "vibris-session.json";
    const std::string legacy_bytes =
        R"({"schema_version":1,"workspace_id":"11111111-1111-4111-8111-111111111111","workspace_id":"22222222-2222-4222-8222-222222222222"})";
    write_bytes(duplicate_legacy, legacy_bytes);
    const auto generated = WorkspaceIdentityStore(generated_identity, duplicate_legacy).load_or_create();
    require(generated != valid_config().workspace_id && vibris::mcp::detail::is_uuid(generated),
        "Duplicate legacy keys were treated as a valid migration source.");
    require(read_bytes(duplicate_legacy) == legacy_bytes, "Duplicate legacy state was rewritten.");

    TempDirectory nested_temp("legacy-nested-duplicate-key");
    const auto nested_identity = nested_temp.path() / ".codex" / "vibris-workspace.json";
    const auto nested_legacy = nested_temp.path() / ".codex" / "vibris-session.json";
    const std::string nested_bytes =
        R"({"schema_version":1,"workspace_id":"11111111-1111-4111-8111-111111111111","extra":{"key":1,"key":2}})";
    write_bytes(nested_legacy, nested_bytes);
    const auto nested_generated = WorkspaceIdentityStore(nested_identity, nested_legacy).load_or_create();
    require(nested_generated != valid_config().workspace_id &&
            vibris::mcp::detail::is_uuid(nested_generated),
        "Nested duplicate legacy keys were treated as a valid migration source.");
    require(read_bytes(nested_legacy) == nested_bytes, "Nested duplicate legacy state was rewritten.");
}

void workspace_identity_enforces_json_depth() {
    constexpr auto maximum_depth = vibris::mcp::detail::kMaxWorkspaceIdentityJsonNestingDepth;
    const auto expected_id = valid_config().workspace_id;
    const auto document = [&](std::size_t nested_containers) {
        return "{\"schema_version\":1,\"workspace_id\":\"" + expected_id +
            "\",\"extra\":" + nested_value(nested_containers) + "}";
    };

    TempDirectory exact_legacy("legacy-depth-exact");
    const auto exact_identity = exact_legacy.path() / ".codex" / "vibris-workspace.json";
    const auto exact_legacy_path = exact_legacy.path() / ".codex" / "vibris-session.json";
    const auto exact_bytes = document(maximum_depth - 1);
    write_bytes(exact_legacy_path, exact_bytes);
    const auto adopted = WorkspaceIdentityStore(exact_identity, exact_legacy_path).load_or_create();
    require(adopted == expected_id, "Exact-depth legacy identity was not accepted.");
    require(read_bytes(exact_legacy_path) == exact_bytes, "Exact-depth legacy state was rewritten.");

    TempDirectory over_legacy("legacy-depth-over");
    const auto generated_identity = over_legacy.path() / ".codex" / "vibris-workspace.json";
    const auto over_legacy_path = over_legacy.path() / ".codex" / "vibris-session.json";
    const auto over_bytes = document(maximum_depth);
    write_bytes(over_legacy_path, over_bytes);
    const auto generated = WorkspaceIdentityStore(generated_identity, over_legacy_path).load_or_create();
    require(generated != expected_id && vibris::mcp::detail::is_uuid(generated),
        "Over-depth legacy identity was accepted as a migration source.");
    require(read_bytes(over_legacy_path) == over_bytes, "Over-depth legacy state was rewritten.");

    for (const auto nested_containers : {maximum_depth - 1, maximum_depth}) {
        TempDirectory identity_temp("identity-depth");
        const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
        const auto legacy_path = identity_temp.path() / ".codex" / "vibris-session.json";
        const auto bytes = document(nested_containers);
        write_bytes(identity_path, bytes);
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
        });
        require(error.code == "INVALID_CONFIG" && !error.retryable && error.message_size <= 512,
            "Nested identity returned the wrong bounded error contract.");
        require(read_bytes(identity_path) == bytes, "Nested identity was rewritten.");
    }
}

void workspace_identity_classifies_nonregular_state() {
    TempDirectory identity_temp("identity-directory");
    const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = identity_temp.path() / ".codex" / "vibris-session.json";
    fs::create_directories(identity_path);
    const auto identity_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
    });
    require(identity_error.code == "INVALID_CONFIG", "Nonregular identity returned the wrong error.");

    TempDirectory legacy_temp("legacy-directory");
    const auto generated_identity = legacy_temp.path() / ".codex" / "vibris-workspace.json";
    const auto directory_legacy = legacy_temp.path() / ".codex" / "vibris-session.json";
    fs::create_directories(directory_legacy);
    const auto generated = WorkspaceIdentityStore(generated_identity, directory_legacy).load_or_create();
    require(vibris::mcp::detail::is_uuid(generated), "Nonregular legacy object did not generate a new identity.");
}

void workspace_identity_rejects_reparse_state() {
    TempDirectory state_link("identity-state-link");
    const auto linked_state = state_link.path() / "linked-state";
    const auto workspace = state_link.path() / "workspace";
    fs::create_directories(linked_state);
    fs::create_directories(workspace);
    std::error_code link_error;
    fs::create_directory_symlink(linked_state, workspace / ".codex", link_error);
    require(!link_error, "Unable to create the state-directory reparse fixture.");
    const auto state_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(workspace / ".codex" / "vibris-workspace.json",
            workspace / ".codex" / "vibris-session.json").load_or_create());
    });
    require(state_error.code == "CONFIG_IO_ERROR" && state_error.retryable,
        "Reparse workspace state directory returned the wrong error contract.");
    require(!fs::exists(linked_state / "vibris-workspace.json"),
        "Reparse workspace state directory redirected identity publication.");

    TempDirectory identity_link("identity-file-link");
    const auto identity_state = identity_link.path() / ".codex";
    const auto identity_target = identity_link.path() / "identity-target.json";
    const std::string identity_target_bytes = R"({"schema_version":1,"workspace_id":"11111111-1111-4111-8111-111111111111"})";
    write_bytes(identity_target, identity_target_bytes);
    fs::create_directories(identity_state);
    link_error.clear();
    fs::create_symlink(identity_target, identity_state / "vibris-workspace.json", link_error);
    require(!link_error, "Unable to create the identity-file reparse fixture.");
    const auto identity_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_state / "vibris-workspace.json",
            identity_state / "vibris-session.json").load_or_create());
    });
    require(identity_error.code == "INVALID_CONFIG" && !identity_error.retryable,
        "Reparse workspace identity returned the wrong error contract.");
    require(read_bytes(identity_target) == identity_target_bytes, "Reparse workspace identity target changed.");

    TempDirectory legacy_link("legacy-file-link");
    const auto legacy_state = legacy_link.path() / ".codex";
    const auto legacy_target = legacy_link.path() / "legacy-target.json";
    const auto legacy_target_bytes = vibris::mcp::detail::serialize_config(valid_config());
    write_bytes(legacy_target, legacy_target_bytes);
    fs::create_directories(legacy_state);
    link_error.clear();
    fs::create_symlink(legacy_target, legacy_state / "vibris-session.json", link_error);
    require(!link_error, "Unable to create the legacy-file reparse fixture.");
    const auto generated = WorkspaceIdentityStore(legacy_state / "vibris-workspace.json",
        legacy_state / "vibris-session.json").load_or_create();
    require(generated != valid_config().workspace_id && vibris::mcp::detail::is_uuid(generated),
        "Reparse legacy state was treated as a migration source.");
    require(read_bytes(legacy_target) == legacy_target_bytes, "Reparse legacy state target changed.");
}

void workspace_identity_maps_access_errors() {
    TempDirectory identity_temp("identity-access-error");
    const auto identity_path = identity_temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = identity_temp.path() / ".codex" / "vibris-session.json";
    static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
    {
        TestHandle locked(CreateFileW(identity_path.c_str(), GENERIC_READ, 0, nullptr, OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL, nullptr));
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Workspace identity access failure returned the wrong error contract.");
    }

    TempDirectory legacy_temp("legacy-access-error");
    const auto generated_identity = legacy_temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path_locked = legacy_temp.path() / ".codex" / "vibris-session.json";
    write_bytes(legacy_path_locked, vibris::mcp::detail::serialize_config(valid_config()));
    {
        TestHandle locked(CreateFileW(legacy_path_locked.c_str(), GENERIC_READ, 0, nullptr, OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL, nullptr));
        const auto error = capture_state_error([&] {
            static_cast<void>(WorkspaceIdentityStore(generated_identity, legacy_path_locked).load_or_create());
        });
        require(error.code == "CONFIG_IO_ERROR" && error.retryable,
            "Legacy state access failure returned the wrong error contract.");
    }
}

void workspace_identity_rejects_active_writer() {
    TempDirectory temp("identity-active-writer");
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
    TestHandle writer(CreateFileW(identity_path.c_str(), GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    const auto error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(identity_path, legacy_path).load_or_create());
    });
    require(error.code == "CONFIG_IO_ERROR" && error.retryable && error.message_size <= 512,
        "Active identity writer did not produce a bounded retryable I/O error.");

    TempDirectory legacy_temp("legacy-active-writer");
    const auto generated_identity = legacy_temp.path() / ".codex" / "vibris-workspace.json";
    const auto active_legacy = legacy_temp.path() / ".codex" / "vibris-session.json";
    write_bytes(active_legacy, vibris::mcp::detail::serialize_config(valid_config()));
    TestHandle legacy_writer(CreateFileW(active_legacy.c_str(), GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    const auto legacy_error = capture_state_error([&] {
        static_cast<void>(WorkspaceIdentityStore(generated_identity, active_legacy).load_or_create());
    });
    require(legacy_error.code == "CONFIG_IO_ERROR" && legacy_error.retryable &&
            legacy_error.message_size <= 512,
        "Active legacy writer did not produce a bounded retryable I/O error.");
}

void same_worktree_backends_coexist() {
    // Given: one Git worktree used by two independent MCP backends.
    TempDirectory temp("same-worktree-backends");
    fs::create_directories(temp.path() / ".git");
    fs::create_directories(temp.path() / "shaders");

    // When: both backends bind to the same canonical workspace root.
    McpBackend first(temp.path(), "127.0.0.1:50051");
    McpBackend second(temp.path(), "127.0.0.1:50051");

    // Then: local MCP state remains available from both instances without backend connectivity.
    const auto first_config = first.dispatch("vibris_get_config", {});
    const auto second_config = second.dispatch("vibris_get_config", {});
    const auto* first_state = std::get_if<vibris::mcp::Json>(&first_config);
    const auto* second_state = std::get_if<vibris::mcp::Json>(&second_config);
    require(first_state != nullptr, "First backend did not expose local config.");
    require(second_state != nullptr, "Second backend did not expose local config.");
    require(first_state->at("workspace_id") == second_state->at("workspace_id"),
            "Same-worktree backends observed different workspace IDs.");
    require(!first_state->at("configured").get<bool>() && first_state->at("config").is_null(),
            "First backend did not start with an empty process-local scene.");
    require(!second_state->at("configured").get<bool>() && second_state->at("config").is_null(),
            "Second backend did not start with an empty process-local scene.");
}

void process_local_scene_configuration() {
    TempDirectory temp("process-local-scene");
    fs::create_directories(temp.path() / ".git");
    fs::create_directories(temp.path() / "shaders");
    const auto pending_root = temp.path() / "pending";
    fs::create_directories(pending_root);
    const auto legacy_path = temp.path() / ".codex" / "vibris-session.json";
    const auto identity_path = temp.path() / ".codex" / "vibris-workspace.json";
    const auto legacy_bytes = vibris::mcp::detail::serialize_config(valid_config());
    write_bytes(legacy_path, legacy_bytes);
    vibris::mcp::test::BackendStateServer server(pending_root);
    std::optional<vibris::mcp::GrpcClientStats> first_stats;
    std::optional<vibris::mcp::GrpcClientStats> second_stats;

    {
        McpBackend first(temp.path(), server.target());
        McpBackend second(temp.path(), server.target());
        const auto identity_bytes = read_bytes(identity_path);

        const auto first_config_outcome = first.dispatch("vibris_get_config", {});
        const auto second_config_outcome = second.dispatch("vibris_get_config", {});
        const auto& first_config = require_json(first_config_outcome, "First backend did not expose local config.");
        const auto& second_config = require_json(second_config_outcome, "Second backend did not expose local config.");
        require(first_config.at("workspace_id") == valid_config().workspace_id &&
                second_config.at("workspace_id") == valid_config().workspace_id,
            "Same-root backends did not share the migrated durable identity.");
        require(!first_config.at("configured").get<bool>() && first_config.at("config").is_null() &&
                !second_config.at("configured").get<bool>() && second_config.at("config").is_null(),
            "Same-root backends did not start with empty process-local scenes.");

        const auto first_status_outcome = first.dispatch("vibris_get_status", {});
        const auto second_status_outcome = second.dispatch("vibris_get_status", {});
        const auto& first_status = require_json(first_status_outcome, "First preconfigure status failed.");
        const auto& second_status = require_json(second_status_outcome, "Second preconfigure status failed.");
        require(first_status.at("workspace_id") == valid_config().workspace_id &&
                second_status.at("workspace_id") == valid_config().workspace_id,
            "Preconfigure status omitted the durable workspace ID.");
        require(!first_status.at("configured").get<bool>() && !second_status.at("configured").get<bool>(),
            "Preconfigure status reported a process-local scene.");

        const auto scene_a_outcome = first.dispatch("vibris_configure", configure_arguments("a", 61.0, 3));
        const auto scene_b_outcome = second.dispatch("vibris_configure", configure_arguments("b", 89.0, 7));
        const auto& scene_a = require_json(scene_a_outcome, "First backend configuration failed.");
        const auto& scene_b = require_json(scene_b_outcome, "Second backend configuration failed.");
        require(scene_a.at("workspace_id") == scene_b.at("workspace_id") &&
                scene_a.at("workspace_id") == valid_config().workspace_id,
            "Process-local scenes changed the durable workspace identity.");
        require(scene_a.at("save_id") == "save-a" && scene_b.at("save_id") == "save-b" &&
                scene_a.at("fov") == 61.0 && scene_b.at("fov") == 89.0,
            "Same-root backends did not keep distinct process-local scenes.");
        require(read_bytes(identity_path) == identity_bytes && read_bytes(legacy_path) == legacy_bytes,
            "Configuration changed durable identity or legacy bytes.");

        const vibris::mcp::Json actions {
            {"actions", vibris::mcp::Json::array({{{"type", "get_shader_status"}}})}};
        static_cast<void>(require_json(first.dispatch("vibris_run_actions", actions),
            "First backend action job failed."));
        static_cast<void>(require_json(second.dispatch("vibris_run_actions", actions),
            "Second backend action job failed."));
        const auto scene_c_outcome = first.dispatch("vibris_configure", configure_arguments("c", 73.0, 11));
        const auto& scene_c = require_json(scene_c_outcome, "First backend reconfiguration failed.");
        require(scene_c.at("save_id") == "save-c" && scene_c.at("fov") == 73.0,
            "First backend did not replace its process-local scene.");
        static_cast<void>(require_json(first.dispatch("vibris_run_actions", actions),
            "First backend action job after reconfigure failed."));
        require(read_bytes(identity_path) == identity_bytes && read_bytes(legacy_path) == legacy_bytes,
            "Runtime jobs changed durable identity or legacy bytes.");

        first_stats = first.shutdown();
        second_stats = second.shutdown();
    }

    require(first_stats && second_stats, "Configured backends did not report gRPC lifecycle statistics.");
    require(first_stats->worker_threads_started == 1 && first_stats->worker_threads_joined == 1 &&
            first_stats->pending_requests == 0,
        "First backend reconnected or leaked gRPC work across configure calls.");
    require(second_stats->worker_threads_started == 1 && second_stats->worker_threads_joined == 1 &&
            second_stats->pending_requests == 0,
        "Second backend reconnected or leaked gRPC work across configure calls.");

    const auto hellos = server.service().hellos();
    const auto jobs = server.service().jobs();
    require(hellos.size() == 2, "Same-root backends did not produce exactly one gRPC hello each.");
    require(jobs.size() == 3, "Fake backend did not observe the expected process-local jobs.");
    std::unordered_set<std::string> process_ids;
    for (const auto& hello : hellos) {
        require(hello.envelope_workspace_id == valid_config().workspace_id &&
                hello.nested_workspace_id == valid_config().workspace_id,
            "gRPC hello did not carry the durable workspace identity.");
        require(hello.message_id == "hello-" + hello.process_id,
            "gRPC hello message and process identities diverged.");
        process_ids.insert(hello.process_id);
    }
    require(process_ids.size() == 2, "Same-root backends reused a process instance identity.");
    const std::array expected_saves {std::string("save-a"), std::string("save-b"), std::string("save-c")};
    const std::array expected_fovs {61.0, 89.0, 73.0};
    for (std::size_t index = 0; index < jobs.size(); ++index) {
        require(jobs[index].envelope_workspace_id == valid_config().workspace_id &&
                jobs[index].nested_workspace_id == valid_config().workspace_id,
            "Submitted job did not carry the durable workspace identity.");
        require(process_ids.contains(jobs[index].process_id),
            "Submitted job did not retain its MCP process identity.");
        require(jobs[index].context.save_id() == expected_saves[index] &&
                jobs[index].context.fov() == expected_fovs[index],
            "Submitted job did not use the expected process-local scene.");
    }
    require(server.service().validation_count() == 3,
        "Fake backend did not validate each process-local configuration.");

    McpBackend restarted(temp.path(), server.target());
    const auto restart_config_outcome = restarted.dispatch("vibris_get_config", {});
    const auto& restart_config = require_json(restart_config_outcome, "Restart did not expose local config state.");
    require(restart_config.at("workspace_id") == valid_config().workspace_id,
        "Restart changed the durable workspace identity.");
    require(!restart_config.at("configured").get<bool>() && restart_config.at("config").is_null(),
        "Restart inherited process-local scene state.");
    const auto restart_status_outcome = restarted.dispatch("vibris_get_status", {});
    const auto& restart_status = require_json(restart_status_outcome, "Restart preconfigure status failed.");
    require(restart_status.at("workspace_id") == valid_config().workspace_id &&
            !restart_status.at("configured").get<bool>(),
        "Restart preconfigure status did not expose an unconfigured durable identity.");
    require(read_bytes(legacy_path) == legacy_bytes,
        "Restart rewrote the legacy scene file.");
    static_cast<void>(restarted.shutdown());
}

void tool_metadata_is_process_local() {
    const vibris::mcp::ToolRegistry tools;
    std::size_t matched = 0;
    for (const auto& definition : tools.definitions()) {
        const auto name = definition.at("name").get<std::string>();
        if (name != "vibris_get_config" && name != "vibris_configure") {
            continue;
        }
        const auto description = definition.at("description").get<std::string>();
        require(description.find("MCP process") != std::string::npos,
            "Scene tool metadata does not describe process-local state.");
        require(description.find("persist") == std::string::npos,
            "Scene tool metadata still describes durable scene persistence.");
        ++matched;
    }
    require(matched == 2, "Scene tool metadata definitions were not found.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 17> test_cases {{
    {"WorkspaceIdentityConcurrentFirstUse", workspace_identity_concurrent_first_use},
    {"WorkspaceIdentityDeterministicPublication", workspace_identity_deterministic_publication},
    {"WorkspaceIdentityIoFailuresCleanup", workspace_identity_io_failures_cleanup},
    {"WorkspaceIdentityMalformedWinner", workspace_identity_malformed_winner},
    {"WorkspaceIdentityRetriesOnlyPublicationLoser", workspace_identity_retries_only_publication_loser},
    {"WorkspaceIdentityBlocksTempSubstitution", workspace_identity_blocks_temp_substitution},
    {"WorkspaceIdentityPreservesCreateCollision", workspace_identity_preserves_create_collision},
    {"WorkspaceIdentityLegacyMigration", workspace_identity_legacy_migration},
    {"WorkspaceIdentityRejectsDuplicateKeys", workspace_identity_rejects_duplicate_keys},
    {"WorkspaceIdentityEnforcesJsonDepth", workspace_identity_enforces_json_depth},
    {"WorkspaceIdentityClassifiesNonregularState", workspace_identity_classifies_nonregular_state},
    {"WorkspaceIdentityRejectsReparseState", workspace_identity_rejects_reparse_state},
    {"WorkspaceIdentityMapsAccessErrors", workspace_identity_maps_access_errors},
    {"WorkspaceIdentityRejectsActiveWriter", workspace_identity_rejects_active_writer},
    {"SameWorktreeBackendsCoexist", same_worktree_backends_coexist},
    {"ProcessLocalSceneConfiguration", process_local_scene_configuration},
    {"ToolMetadataIsProcessLocal", tool_metadata_is_process_local},
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
