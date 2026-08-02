#include "git_repository.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace vibris::mcp {

struct GitRepositorySecurityAccess final {
    static GitArchivePipe launch(const std::filesystem::path& executable,
        const std::vector<std::wstring>& arguments) {
        return GitRepository::launch_git(arguments, executable);
    }
};

}

namespace {

namespace fs = std::filesystem;

std::wstring environment_variable(const wchar_t* name) {
    const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0) {
        throw std::runtime_error("Required environment variable is missing.");
    }
    std::wstring value(size, L'\0');
    const DWORD copied = GetEnvironmentVariableW(name, value.data(), size);
    if (copied == 0 || copied >= size) {
        throw std::runtime_error("Unable to read an environment variable.");
    }
    value.resize(copied);
    return value;
}

std::optional<std::wstring> optional_environment_variable(const wchar_t* name) {
    SetLastError(ERROR_SUCCESS);
    const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0 && GetLastError() == ERROR_ENVVAR_NOT_FOUND) return std::nullopt;
    if (size == 0) throw std::runtime_error("Unable to size an environment variable.");
    std::wstring value(size, L'\0');
    const DWORD copied = GetEnvironmentVariableW(name, value.data(), size);
    if (copied == 0 || copied >= size) throw std::runtime_error("Unable to read an environment variable.");
    value.resize(copied);
    return value;
}

fs::path module_path() {
    std::wstring value(32768, L'\0');
    const DWORD copied = GetModuleFileNameW(nullptr, value.data(), static_cast<DWORD>(value.size()));
    if (copied == 0 || copied == value.size()) {
        throw std::runtime_error("Unable to locate the security test executable.");
    }
    value.resize(copied);
    return value;
}

fs::path trusted_git_from_path() {
    std::wstring path = environment_variable(L"PATH");
    std::size_t start = 0;
    while (start <= path.size()) {
        const auto end = path.find(L';', start);
        auto entry = path.substr(start, end == std::wstring::npos ? path.size() - start : end - start);
        if (entry.size() >= 2 && entry.front() == L'"' && entry.back() == L'"') {
            entry = entry.substr(1, entry.size() - 2);
        }
        const fs::path directory(entry);
        std::error_code error;
        const auto candidate = directory / L"git.exe";
        if (directory.is_absolute() && fs::is_regular_file(candidate, error)) {
            return candidate.lexically_normal();
        }
        if (end == std::wstring::npos) {
            break;
        }
        start = end + 1;
    }
    throw std::runtime_error("git.exe was not found in an absolute PATH entry.");
}

std::wstring quote_argument(std::wstring_view argument) {
    std::wstring result = L"\"";
    std::size_t backslashes = 0;
    for (const wchar_t character : argument) {
        if (character == L'\\') {
            ++backslashes;
        } else {
            result.append(backslashes + (character == L'"' ? backslashes + 1 : 0), L'\\');
            result.push_back(character);
            backslashes = 0;
        }
    }
    result.append(backslashes * 2, L'\\');
    return result + L'"';
}

void run_git(const fs::path& executable, const std::vector<std::wstring>& arguments) {
    std::wstring command = quote_argument(executable.wstring());
    for (const auto& argument : arguments) {
        command += L' ' + quote_argument(argument);
    }
    STARTUPINFOW startup{sizeof(startup)};
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr,
                        &startup, &process)) {
        throw std::runtime_error("Unable to start trusted git.exe.");
    }
    CloseHandle(process.hThread);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD exit_code = 0;
    const BOOL read_exit_code = GetExitCodeProcess(process.hProcess, &exit_code);
    CloseHandle(process.hProcess);
    if (!read_exit_code || exit_code != 0) {
        throw std::runtime_error("Trusted git.exe failed while preparing the test repository.");
    }
}

std::string read_trimmed(const fs::path& path) {
    std::ifstream input(path);
    std::string value((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    while (!value.empty() && (value.back() == '\r' || value.back() == '\n')) {
        value.pop_back();
    }
    return value;
}

class TempDirectory final {
public:
    TempDirectory() {
        std::wstring root(MAX_PATH, L'\0');
        const DWORD copied = GetTempPathW(static_cast<DWORD>(root.size()), root.data());
        if (copied == 0 || copied >= root.size()) {
            throw std::runtime_error("Unable to locate the temporary directory.");
        }
        root.resize(copied);
        std::wstring name(MAX_PATH, L'\0');
        if (GetTempFileNameW(root.c_str(), L"vgs", 0, name.data()) == 0) {
            throw std::runtime_error("Unable to reserve a temporary test path.");
        }
        name.resize(std::char_traits<wchar_t>::length(name.c_str()));
        fs::remove(name);
        path_ = name;
        fs::create_directory(path_);
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

class ProcessStateGuard final {
public:
    ProcessStateGuard()
        : directory_(fs::current_path()), path_(environment_variable(L"PATH")),
          git_dir_(optional_environment_variable(L"GIT_DIR")),
          git_work_tree_(optional_environment_variable(L"GIT_WORK_TREE")) {
    }
    ~ProcessStateGuard() {
        SetCurrentDirectoryW(directory_.c_str());
        SetEnvironmentVariableW(L"PATH", path_.c_str());
        SetEnvironmentVariableW(L"GIT_DIR", git_dir_ ? git_dir_->c_str() : nullptr);
        SetEnvironmentVariableW(L"GIT_WORK_TREE", git_work_tree_ ? git_work_tree_->c_str() : nullptr);
        SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MARKER", nullptr);
        SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MODE", nullptr);
    }

private:
    fs::path directory_;
    std::wstring path_;
    std::optional<std::wstring> git_dir_;
    std::optional<std::wstring> git_work_tree_;
};

int run_decoy() {
    const auto marker = environment_variable(L"VIBRIS_GIT_DECOY_MARKER");
    std::ofstream(fs::path(marker)) << GetCurrentProcessId() << '\n';
    const auto mode = optional_environment_variable(L"VIBRIS_GIT_DECOY_MODE");
    if (mode && *mode == L"hang") Sleep(INFINITE);
    return 73;
}

void reject_working_directory_git() {
    const auto trusted_git = trusted_git_from_path();
    TempDirectory temporary;
    const auto repository = temporary.path() / L"repository";
    fs::create_directory(repository);
    run_git(trusted_git, {L"init", L"--quiet", repository.wstring()});
    run_git(trusted_git, {L"-C", repository.wstring(), L"-c", L"user.name=Vibris Test", L"-c",
                          L"user.email=vibris@example.invalid", L"commit", L"--quiet", L"--allow-empty", L"-m",
                          L"initial"});

    const auto head = read_trimmed(repository / L".git" / L"HEAD");
    if (!head.starts_with("ref: ")) {
        throw std::runtime_error("Test repository HEAD is not symbolic.");
    }
    const auto expected = read_trimmed(repository / L".git" / fs::path(head.substr(5)));
    const auto marker = temporary.path() / L"decoy-ran";
    if (!CopyFileW(module_path().c_str(), (repository / L"git.exe").c_str(), FALSE)) {
        throw std::runtime_error("Unable to copy the decoy git.exe.");
    }

    ProcessStateGuard restore;
    SetEnvironmentVariableW(L"PATH", trusted_git.parent_path().c_str());
    SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MARKER", marker.c_str());
    SetCurrentDirectoryW(repository.c_str());

    std::string actual;
    std::string failure;
    try {
        actual = vibris::mcp::GitRepository(repository).resolve_commit("HEAD");
    } catch (const std::exception& error) {
        failure = error.what();
    }
    if (fs::exists(marker)) {
        throw std::runtime_error("Decoy git.exe was executed from the repository working directory.");
    }
    if (!failure.empty()) {
        throw std::runtime_error("resolve_commit failed: " + failure);
    }
    if (actual != expected) {
        throw std::runtime_error("resolve_commit did not return the real repository HEAD.");
    }
}

std::string initialize_repository(const fs::path& git, const fs::path& repository, const char* payload) {
    fs::create_directories(repository / L"shaders");
    std::ofstream(repository / L"shaders" / L"payload.glsl") << payload;
    run_git(git, {L"init", L"--quiet", repository.wstring()});
    run_git(git, {L"-C", repository.wstring(), L"add", L"--", L"shaders/payload.glsl"});
    run_git(git, {L"-C", repository.wstring(), L"-c", L"user.name=Vibris Test", L"-c",
        L"user.email=vibris@example.invalid", L"commit", L"--quiet", L"-m", L"initial"});
    const auto head = read_trimmed(repository / L".git" / L"HEAD");
    if (!head.starts_with("ref: ")) throw std::runtime_error("Test repository HEAD is not symbolic.");
    return read_trimmed(repository / L".git" / fs::path(head.substr(5)));
}

void poisoned_git_environment_is_ignored() {
    const auto git = trusted_git_from_path();
    TempDirectory temporary;
    const auto first = temporary.path() / L"first";
    const auto second = temporary.path() / L"second";
    const auto expected = initialize_repository(git, first, "first\n");
    const auto other = initialize_repository(git, second, "second\n");
    if (expected == other) throw std::runtime_error("Poison fixture repositories have the same commit.");

    ProcessStateGuard restore;
    SetEnvironmentVariableW(L"GIT_DIR", (second / L".git").c_str());
    SetEnvironmentVariableW(L"GIT_WORK_TREE", second.c_str());
    vibris::mcp::GitRepository repository(first);
    if (repository.resolve_commit("HEAD") != expected) {
        throw std::runtime_error("Poisoned GIT_DIR redirected commit resolution.");
    }
    auto archive = repository.open_shader_archive(expected);
    std::array<std::byte, 4096> buffer{};
    std::size_t total = 0;
    for (auto count = archive.read(buffer); count != 0; count = archive.read(buffer)) total += count;
    if (archive.wait() != 0 || total == 0) {
        throw std::runtime_error("Poisoned GIT_DIR redirected archive production.");
    }
}

bool process_running(DWORD process_id) {
    const auto process = OpenProcess(SYNCHRONIZE, FALSE, process_id);
    if (process == nullptr) return false;
    const auto result = WaitForSingleObject(process, 0);
    CloseHandle(process);
    return result == WAIT_TIMEOUT;
}

void hanging_git_is_bounded_and_reaped() {
    TempDirectory temporary;
    const auto fake_git = temporary.path() / L"git.exe";
    const auto marker = temporary.path() / L"git.pid";
    if (!CopyFileW(module_path().c_str(), fake_git.c_str(), FALSE)) {
        throw std::runtime_error("Unable to copy the hanging Git fixture.");
    }
    ProcessStateGuard restore;
    SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MARKER", marker.c_str());
    SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MODE", L"hang");
    const auto started = std::chrono::steady_clock::now();
    bool timed_out = false;
    try {
        auto child = vibris::mcp::GitRepositorySecurityAccess::launch(
            fake_git, {L"git", L"rev-parse", L"HEAD"});
        std::array<std::byte, 1> byte{};
        static_cast<void>(child.read(byte));
    } catch (const vibris::mcp::StateError& error) {
        timed_out = std::string(error.what()).find("five-second deadline") != std::string::npos;
    }
    const auto elapsed = std::chrono::steady_clock::now() - started;
    std::ifstream input(marker);
    DWORD child_pid = 0;
    input >> child_pid;
    if (!timed_out || child_pid == 0 || process_running(child_pid) ||
        elapsed < std::chrono::milliseconds(4500) || elapsed > std::chrono::milliseconds(7500)) {
        throw std::runtime_error("Hanging Git was not bounded and reaped by exact owned process handle.");
    }
}

} // namespace

int main() {
    try {
        if (_wcsicmp(module_path().filename().c_str(), L"git.exe") == 0) {
            return run_decoy();
        }
        reject_working_directory_git();
        poisoned_git_environment_is_ignored();
        hanging_git_is_bounded_and_reaped();
        std::cout << "PASS git repository trust, environment isolation, deadline, and owned cleanup\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
