#include "git_repository.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

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
    ProcessStateGuard() : directory_(fs::current_path()), path_(environment_variable(L"PATH")) {}
    ~ProcessStateGuard() {
        SetCurrentDirectoryW(directory_.c_str());
        SetEnvironmentVariableW(L"PATH", path_.c_str());
        SetEnvironmentVariableW(L"VIBRIS_GIT_DECOY_MARKER", nullptr);
    }

private:
    fs::path directory_;
    std::wstring path_;
};

int run_decoy() {
    const auto marker = environment_variable(L"VIBRIS_GIT_DECOY_MARKER");
    std::ofstream(fs::path(marker)).put('1');
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

} // namespace

int main() {
    try {
        if (_wcsicmp(module_path().filename().c_str(), L"git.exe") == 0) {
            return run_decoy();
        }
        reject_working_directory_git();
        std::cout << "PASS git repository ignores working-directory git.exe\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}