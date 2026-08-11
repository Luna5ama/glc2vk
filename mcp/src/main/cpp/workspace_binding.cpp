#include "workspace_binding.hpp"

#include "git_process.hpp"
#include "state_error.hpp"

#include <array>
#include <chrono>
#include <cstddef>
#include <cwchar>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::size_t kGitMaxOutput = 32768;

class UniqueHandle final {
public:
    UniqueHandle() = default;

    explicit UniqueHandle(const HANDLE handle)
        : handle_(handle) {
    }

    ~UniqueHandle() {
        reset();
    }

    UniqueHandle(const UniqueHandle&) = delete;
    UniqueHandle& operator=(const UniqueHandle&) = delete;

    [[nodiscard]] HANDLE get() const noexcept {
        return handle_;
    }

    void reset(const HANDLE handle = nullptr) noexcept {
        if (handle_ != nullptr && handle_ != INVALID_HANDLE_VALUE) CloseHandle(handle_);
        handle_ = handle;
    }

private:
    HANDLE handle_ = nullptr;
};

class ProcThreadAttributeList final {
public:
    ~ProcThreadAttributeList() {
        if (list_ != nullptr) DeleteProcThreadAttributeList(list_);
    }

    ProcThreadAttributeList(const ProcThreadAttributeList&) = delete;
    ProcThreadAttributeList& operator=(const ProcThreadAttributeList&) = delete;

    ProcThreadAttributeList() = default;

    bool initialize() {
        SIZE_T bytes = 0;
        InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
        if (bytes == 0) return false;
        storage_.resize(bytes);
        list_ = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage_.data());
        if (!InitializeProcThreadAttributeList(list_, 1, 0, &bytes)) {
            list_ = nullptr;
            return false;
        }
        return true;
    }

    [[nodiscard]] LPPROC_THREAD_ATTRIBUTE_LIST get() const noexcept {
        return list_;
    }

private:
    std::vector<std::byte> storage_;
    LPPROC_THREAD_ATTRIBUTE_LIST list_ = nullptr;
};

enum class PipeDrainResult {
    drained,
    timed_out,
    overflow,
    failed,
};

PipeDrainResult drain_available_pipe_output(
    const HANDLE pipe, std::string& output, const std::chrono::steady_clock::time_point deadline) {
    std::array<char, 4096> buffer{};
    for (;;) {
        if (std::chrono::steady_clock::now() >= deadline) return PipeDrainResult::timed_out;
        DWORD available = 0;
        if (!PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr)) {
            return GetLastError() == ERROR_BROKEN_PIPE ? PipeDrainResult::drained :
                                                        PipeDrainResult::failed;
        }
        if (available == 0) return PipeDrainResult::drained;

        const auto requested = available < buffer.size() ? available : static_cast<DWORD>(buffer.size());
        DWORD read = 0;
        if (!ReadFile(pipe, buffer.data(), requested, &read, nullptr)) {
            return GetLastError() == ERROR_BROKEN_PIPE ? PipeDrainResult::drained :
                                                        PipeDrainResult::failed;
        }
        if (read == 0) return PipeDrainResult::drained;
        if (read > kGitMaxOutput - output.size()) return PipeDrainResult::overflow;
        output.append(buffer.data(), read);
    }
}

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

bool git_toplevel(const fs::path& root, std::string& output) {
    output.clear();
    fs::path git_executable;
    try {
        git_executable = resolve_git_executable();
    } catch (const StateError&) {
        return false;
    }

    const std::array<std::wstring, 8> arguments{
        git_executable.wstring(), L"-c", L"core.quotePath=false", L"-C", root.wstring(),
        L"rev-parse", L"--path-format=absolute", L"--show-toplevel",
    };
    std::wstring command;
    for (const auto& argument : arguments) {
        if (!command.empty()) command.push_back(L' ');
        command += quote_git_argument(argument);
    }
    if (command.size() >= 32767) return false;

    const auto deadline = std::chrono::steady_clock::now() + kGitOperationTimeout;

    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    HANDLE pipe_read_raw = nullptr;
    HANDLE pipe_write_raw = nullptr;
    if (!CreatePipe(&pipe_read_raw, &pipe_write_raw, &security, 0)) return false;
    UniqueHandle pipe_read(pipe_read_raw);
    UniqueHandle pipe_write(pipe_write_raw);
    if (!SetHandleInformation(pipe_read.get(), HANDLE_FLAG_INHERIT, 0)) return false;

    ProcThreadAttributeList attributes;
    if (!attributes.initialize()) return false;
    HANDLE inherited_pipe = pipe_write.get();
    if (!UpdateProcThreadAttribute(attributes.get(), 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
            &inherited_pipe, sizeof(inherited_pipe), nullptr, nullptr)) {
        return false;
    }

    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = INVALID_HANDLE_VALUE;
    startup.StartupInfo.hStdOutput = pipe_write.get();
    startup.StartupInfo.hStdError = pipe_write.get();
    startup.lpAttributeList = attributes.get();
    PROCESS_INFORMATION process{};
    auto environment = sanitized_git_environment();
    const BOOL created = CreateProcessW(
        git_executable.c_str(), command.data(), nullptr, nullptr, TRUE,
        EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT,
        environment.data(), nullptr, &startup.StartupInfo, &process);
    pipe_write.reset();
    if (!created) return false;
    UniqueHandle process_handle(process.hProcess);
    UniqueHandle thread_handle(process.hThread);
    thread_handle.reset();

    bool completed = false;
    for (;;) {
        const auto drain_result = drain_available_pipe_output(pipe_read.get(), output, deadline);
        if (drain_result != PipeDrainResult::drained) break;

        const auto wait_result = WaitForSingleObject(process_handle.get(), 0);
        if (wait_result == WAIT_OBJECT_0) {
            completed = true;
            break;
        }
        if (wait_result == WAIT_FAILED) {
            break;
        }

        const auto now = std::chrono::steady_clock::now();
        if (now >= deadline) break;
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
        const auto wait_milliseconds = static_cast<DWORD>(
            remaining < kGitPollMilliseconds ? (remaining > 0 ? remaining : 1) : kGitPollMilliseconds);
        if (WaitForSingleObject(process_handle.get(), wait_milliseconds) == WAIT_FAILED) {
            break;
        }
    }

    if (!completed) {
        static_cast<void>(terminate_and_reap_git(process_handle.get()));
        output.clear();
        return false;
    }

    if (drain_available_pipe_output(pipe_read.get(), output, deadline) != PipeDrainResult::drained) {
        output.clear();
        return false;
    }
    DWORD exit_code = 0;
    return GetExitCodeProcess(process_handle.get(), &exit_code) != FALSE && exit_code == 0;
}

bool is_git_worktree_root(const fs::path& root) {
    if (!has_git_marker(root)) return false;

    std::string output;
    if (!git_toplevel(root, output)) return false;
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n')) output.pop_back();
    if (output.empty() || output.find_first_of("\r\n") != std::string::npos ||
        output.find('\0') != std::string::npos) {
        return false;
    }

    const auto wide_size = MultiByteToWideChar(
        CP_UTF8, MB_ERR_INVALID_CHARS, output.data(), static_cast<int>(output.size()), nullptr, 0);
    if (wide_size <= 0) return false;
    std::wstring wide_path(static_cast<std::size_t>(wide_size), L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, output.data(), static_cast<int>(output.size()),
            wide_path.data(), wide_size) != wide_size) {
        return false;
    }

    std::error_code error;
    const auto reported_root = fs::canonical(fs::path(wide_path), error);
    return !error && fs::equivalent(root, reported_root, error) && !error;
}

} // namespace

WorkspaceBinding resolve_workspace(const fs::path& requested_root) {
    if (requested_root.empty() || !requested_root.is_absolute()) {
        throw StateError(kInvalidWorktreeCode, "Worktree root must be an absolute path.");
    }
    const auto root = canonical_directory(requested_root);
    if (!is_git_worktree_root(root)) {
        throw StateError(kInvalidWorktreeCode, "Worktree root must name a Git worktree root.");
    }
    return {
        root,
        root / ".vibris" / "workspace.json",
    };
}

} // namespace vibris::mcp
