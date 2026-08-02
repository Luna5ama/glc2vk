#include "git_executable_resolver.hpp"
#include "state_error.hpp"
#include "workspace_binding.hpp"

#include <chrono>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <optional>
#include <process.h>
#include <stdexcept>
#include <string>
#include <system_error>
#include <type_traits>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using vibris::mcp::StateError;
using vibris::mcp::WorkspaceBinding;
using vibris::mcp::kInvalidWorktreeCode;
using vibris::mcp::resolve_workspace;

namespace {

constexpr wchar_t kDelayedGitPidFileVariable[] = L"VIBRIS_TEST_DELAYED_GIT_PID_FILE";

fs::path current_executable_path() {
    std::vector<wchar_t> buffer(32768, L'\0');
    const auto length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (length == 0 || length >= buffer.size()) {
        throw std::runtime_error("Could not resolve the focused test executable path.");
    }
    return fs::path(std::wstring(buffer.data(), length));
}

std::optional<int> run_delayed_git_fixture() {
    const auto executable = current_executable_path();
    if (_wcsicmp(executable.filename().c_str(), L"git.exe") != 0) return std::nullopt;

    std::vector<wchar_t> pid_file_buffer(32768, L'\0');
    const auto length = GetEnvironmentVariableW(kDelayedGitPidFileVariable, pid_file_buffer.data(),
        static_cast<DWORD>(pid_file_buffer.size()));
    if (length == 0 || length >= pid_file_buffer.size()) return 125;
    std::ofstream pid_file(fs::path(std::wstring(pid_file_buffer.data(), length)));
    pid_file << _getpid() << '\n';
    pid_file.close();
    Sleep(9000);
    return 0;
}

class TempDirectory final {
public:
    TempDirectory()
        : path_(create_unique_directory()) {
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
                std::to_string(_getpid()) + "-" +
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

void run_git(std::vector<std::wstring> arguments) {
    const auto executable = vibris::mcp::resolve_git_executable();
    arguments.insert(arguments.begin(), L"git");
    std::vector<const wchar_t*> argument_pointers;
    argument_pointers.reserve(arguments.size() + 1);
    for (const auto& argument : arguments) argument_pointers.push_back(argument.c_str());
    argument_pointers.push_back(nullptr);

    errno = 0;
    const auto result = _wspawnv(_P_WAIT, executable.c_str(), argument_pointers.data());
    if (result != 0) {
        throw std::runtime_error(
            "Git fixture command failed with exit " + std::to_string(result) +
            " and errno " + std::to_string(errno) + ".");
    }
}

void initialize_repository(const fs::path& repository) {
    run_git({L"init", L"--quiet", repository.wstring()});
}

void create_initial_commit(const fs::path& repository) {
    std::ofstream(repository / "tracked.txt") << "tracked\n";
    run_git({L"-c", L"core.autocrlf=false", L"-C", repository.wstring(), L"add", L"--",
        L"tracked.txt"});
    run_git({L"-c", L"user.name=Vibris-Test", L"-c", L"user.email=vibris@example.invalid",
        L"-C", repository.wstring(), L"commit", L"--quiet", L"-m", L"initial"});
}

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

std::optional<std::wstring> read_environment_variable(const wchar_t* name) {
    SetLastError(ERROR_SUCCESS);
    const auto required = GetEnvironmentVariableW(name, nullptr, 0);
    if (required == 0) {
        if (GetLastError() == ERROR_ENVVAR_NOT_FOUND) return std::nullopt;
        throw std::runtime_error("Could not size a test environment variable.");
    }
    std::vector<wchar_t> buffer(required, L'\0');
    const auto copied = GetEnvironmentVariableW(name, buffer.data(), required);
    if (copied == 0 || copied >= required) {
        throw std::runtime_error("Could not read a test environment variable.");
    }
    return std::wstring(buffer.data(), copied);
}

class EnvironmentVariableGuard final {
public:
    EnvironmentVariableGuard(const wchar_t* name, const std::wstring& value)
        : name_(name), original_(read_environment_variable(name)) {
        require(SetEnvironmentVariableW(name_.c_str(), value.c_str()) != FALSE,
            "Could not set a test environment variable.");
    }

    ~EnvironmentVariableGuard() {
        SetEnvironmentVariableW(name_.c_str(), original_ ? original_->c_str() : nullptr);
    }

    EnvironmentVariableGuard(const EnvironmentVariableGuard&) = delete;
    EnvironmentVariableGuard& operator=(const EnvironmentVariableGuard&) = delete;

private:
    std::wstring name_;
    std::optional<std::wstring> original_;
};

bool process_is_running(const DWORD process_id) {
    const auto process = OpenProcess(SYNCHRONIZE, FALSE, process_id);
    if (process == nullptr) return false;
    const auto wait_result = WaitForSingleObject(process, 0);
    CloseHandle(process);
    require(wait_result != WAIT_FAILED, "Could not inspect the owned delayed Git child.");
    return wait_result == WAIT_TIMEOUT;
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
    TempDirectory worktree;
    initialize_repository(worktree.path());
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
    TempDirectory fixture;
    const auto main_worktree = fixture.path() / "main";
    const auto linked_worktree = fixture.path() / "linked";
    initialize_repository(main_worktree);
    create_initial_commit(main_worktree);
    run_git({L"-C", main_worktree.wstring(), L"worktree", L"add", L"--quiet", L"--detach",
        linked_worktree.wstring(), L"HEAD"});
    require(fs::is_regular_file(linked_worktree / ".git"),
        "LinkedWorktreeFixture: git worktree did not create a .git file.");
    const auto nested = linked_worktree / "shaders";
    fs::create_directory(nested);

    CurrentDirectoryGuard cwd;
    cwd.set(nested);
    const auto binding = resolve_workspace();

    require_path_equal(binding.root, linked_worktree, "LinkedWorktreeGitFile");
    require_identity_paths(binding, "LinkedWorktreeGitFile");
}

void independent_repositories_remain_distinct() {
    TempDirectory first;
    TempDirectory second;
    initialize_repository(first.path());
    initialize_repository(second.path());
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
    TempDirectory cwd_worktree;
    TempDirectory explicit_fixture;
    const auto initial_explicit_worktree = explicit_fixture.path() / "explicit";
    const auto explicit_worktree = explicit_fixture.path() / L"explicit & \u5b89\u5168";
    initialize_repository(cwd_worktree.path());
    initialize_repository(initial_explicit_worktree);
    fs::rename(initial_explicit_worktree, explicit_worktree);
    fs::create_directories(cwd_worktree.path() / "nested");
    fs::create_directories(explicit_worktree / "canonical-child");
    const auto noncanonical_explicit = explicit_worktree / "canonical-child" / "..";

    CurrentDirectoryGuard cwd;
    cwd.set(cwd_worktree.path() / "nested");
    const auto binding = resolve_workspace(noncanonical_explicit);

    require_path_equal(binding.root, explicit_worktree, "ExplicitOverrideCanonical");
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

bool accepts_explicit(const fs::path& root) {
    try {
        static_cast<void>(resolve_workspace(root));
        return true;
    } catch (const StateError& error) {
        require(error.code() == kInvalidWorktreeCode, "FakeMarkerExplicit: wrong structured error code.");
        return false;
    }
}

bool accepts_implicit(const fs::path& root) {
    const auto nested = root / "nested";
    fs::create_directory(nested);
    CurrentDirectoryGuard cwd;
    cwd.set(nested);
    try {
        static_cast<void>(resolve_workspace());
        return true;
    } catch (const StateError& error) {
        require(error.code() == kInvalidWorktreeCode, "FakeMarkerImplicit: wrong structured error code.");
        return false;
    }
}

void fake_git_markers_are_rejected() {
    TempDirectory fake_directory;
    require(!has_git_ancestor(fake_directory.path().parent_path()),
        "FakeMarkerFixture: temporary parent unexpectedly has a Git ancestor.");
    fs::create_directory(fake_directory.path() / ".git");

    TempDirectory fake_gitdir_file;
    std::ofstream(fake_gitdir_file.path() / ".git") << "gitdir: ../missing-git-dir\n";

    const auto directory_explicit = accepts_explicit(fake_directory.path());
    const auto directory_implicit = accepts_implicit(fake_directory.path());
    const auto file_explicit = accepts_explicit(fake_gitdir_file.path());
    const auto file_implicit = accepts_implicit(fake_gitdir_file.path());

    require(!directory_explicit && !directory_implicit && !file_explicit && !file_implicit,
        "FakeGitMarkers: fake_directory_explicit=" + std::to_string(directory_explicit) +
        " fake_directory_implicit=" + std::to_string(directory_implicit) +
        " fake_gitdir_file_explicit=" + std::to_string(file_explicit) +
        " fake_gitdir_file_implicit=" + std::to_string(file_implicit) + ".");
}

void malformed_and_nonworktree_gitdir_targets_are_rejected() {
    TempDirectory malformed;
    std::ofstream(malformed.path() / ".git") << "not a gitdir\n";
    require_invalid_worktree(
        [&] { static_cast<void>(resolve_workspace(malformed.path())); }, "MalformedGitdirFile");

    TempDirectory nonworktree;
    const auto ordinary_target = nonworktree.path() / "ordinary-target";
    fs::create_directory(ordinary_target);
    std::ofstream(nonworktree.path() / ".git") << "gitdir: " << ordinary_target.string() << '\n';
    require_invalid_worktree(
        [&] { static_cast<void>(resolve_workspace(nonworktree.path())); }, "NonworktreeGitdirTarget");

    TempDirectory bare_repository;
    run_git({L"init", L"--bare", L"--quiet", (bare_repository.path() / ".git").wstring()});
    require_invalid_worktree(
        [&] { static_cast<void>(resolve_workspace(bare_repository.path())); }, "BareRepositoryMarker");
}

void path_spoofed_git_is_ignored() {
    TempDirectory fixture;
    const auto repository = fixture.path() / "repository";
    initialize_repository(repository);

    const auto fake_bin = fixture.path() / "fake-bin";
    fs::create_directory(fake_bin);
    const auto fake_git = fake_bin / "git.exe";
    fs::copy_file(current_executable_path(), fake_git);
    const auto pid_file = fixture.path() / "delayed-git.pid";

    const auto original_path = read_environment_variable(L"PATH");
    require(original_path.has_value(), "DelayedGitFixture: PATH is unavailable.");
    EnvironmentVariableGuard path_guard(L"PATH", fake_bin.wstring() + L";" + *original_path);
    EnvironmentVariableGuard pid_file_guard(kDelayedGitPidFileVariable, pid_file.wstring());

    const auto started = std::chrono::steady_clock::now();
    const auto binding = resolve_workspace(repository);
    const auto elapsed = std::chrono::steady_clock::now() - started;
    require_path_equal(binding.root, repository, "PathSpoofedGitIgnored");
    require(elapsed < std::chrono::milliseconds(2500) && !fs::exists(pid_file),
        "PathSpoofedGitIgnored: decoy executable ran or trusted Git was unexpectedly slow.");
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
    if (const auto fixture_exit = run_delayed_git_fixture()) return *fixture_exit;

    static_assert(std::is_same_v<decltype(&resolve_workspace),
        WorkspaceBinding (*)(std::optional<fs::path>)>,
        "Workspace routing must remain one immutable process-start root or explicit override.");

    try {
        nested_cwd_discovers_dirty_worktree();
        linked_worktree_git_file_is_accepted();
        independent_repositories_remain_distinct();
        explicit_root_is_canonical_and_overrides_cwd();
        malformed_roots_are_rejected();
        fake_git_markers_are_rejected();
        malformed_and_nonworktree_gitdir_targets_are_rejected();
        path_spoofed_git_is_ignored();
        cwd_outside_any_worktree_is_rejected();
        std::cout << "PASS WorktreeDiscovery\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL WorktreeDiscovery: " << error.what() << '\n';
        return 1;
    }
}
