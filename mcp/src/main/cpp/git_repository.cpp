#include "git_repository.hpp"
#include "git_executable_resolver.hpp"
#include "state_error.hpp"
#include <algorithm>
#include <array>
#include <cctype>
#include <thread>
#include <utility>
#include <vector>
namespace vibris::mcp {
namespace {
using Handle = HANDLE;
void close_handle(void *&value) noexcept {
    if (value != nullptr) {
        CloseHandle(static_cast<Handle>(value));
        value = nullptr;
    }
}
std::wstring quote_argument(std::wstring_view argument) {
    if (!argument.empty() && argument.find_first_of(L" \t\"") == std::wstring_view::npos) {
        return std::wstring(argument);
    }
    std::wstring quoted(1, L'"');
    std::size_t backslashes = 0;
    for (const wchar_t character : argument) {
        if (character == L'\\') {
            ++backslashes;
        } else if (character == L'"') {
            quoted.append(backslashes * 2 + 1, L'\\');
            quoted.push_back(L'"');
            backslashes = 0;
        } else {
            quoted.append(backslashes, L'\\');
            quoted.push_back(character);
            backslashes = 0;
        }
    }
    quoted.append(backslashes * 2, L'\\');
    quoted.push_back(L'"');
    return quoted;
}
std::wstring command_line(const std::vector<std::wstring> &arguments) {
    std::wstring command;
    for (const auto &argument : arguments) {
        if (!command.empty()) {
            command.push_back(L' ');
        }
        command += quote_argument(argument);
    }
    if (command.size() >= 32767) {
        throw StateError("COMMIT_NOT_FOUND", "Git revision command is too long.");
    }
    return command;
}
struct PipePair final {
    Handle read = nullptr;
    Handle write = nullptr;
    PipePair() = default;
    PipePair(const PipePair &) = delete;
    PipePair &operator=(const PipePair &) = delete;
    PipePair(PipePair &&other) noexcept
        : read(std::exchange(other.read, nullptr)), write(std::exchange(other.write, nullptr)) {}
    ~PipePair() {
        if (read != nullptr) {
            CloseHandle(read);
        }
        if (write != nullptr) {
            CloseHandle(write);
        }
    }
};
PipePair make_pipe() {
    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    PipePair pipe;
    if (!CreatePipe(&pipe.read, &pipe.write, &security, 0) ||
        !SetHandleInformation(pipe.read, HANDLE_FLAG_INHERIT, 0)) {
        throw StateError("INTERNAL_ERROR", "Unable to create a Git process pipe.", true);
    }
    return pipe;
}
} // namespace
struct GitArchivePipe::StderrDrain final {
    explicit StderrDrain(Handle handle) : handle(handle), worker([this] { drain(); }) {}
    ~StderrDrain() {
        if (worker.joinable()) {
            worker.join();
        }
        if (handle != nullptr)
            CloseHandle(handle);
    }
    void drain() noexcept {
        std::array<char, 4096> buffer{};
        DWORD read = 0;
        while (ReadFile(handle, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr) && read != 0) {
        }
    }
    void join() noexcept {
        if (worker.joinable()) {
            worker.join();
        }
    }
    Handle handle;
    std::thread worker;
};
GitArchivePipe GitRepository::launch_git(const std::vector<std::wstring> &arguments) {
    auto stdout_pipe = make_pipe();
    auto stderr_pipe = make_pipe();
    const auto git_executable = resolve_git_executable();
    auto resolved_arguments = arguments;
    resolved_arguments.front() = git_executable.wstring();
    auto command = command_line(resolved_arguments);
    std::array<Handle, 2> inherited{stdout_pipe.write, stderr_pipe.write};
    SIZE_T attribute_bytes = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attribute_bytes);
    std::vector<std::byte> attributes(attribute_bytes);
    auto *attribute_list = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributes.data());
    if (!InitializeProcThreadAttributeList(attribute_list, 1, 0, &attribute_bytes)) {
        throw StateError("INTERNAL_ERROR", "Unable to restrict inherited Git process handles.", true);
    }
    if (!UpdateProcThreadAttribute(attribute_list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited.data(),
                                   sizeof(inherited), nullptr, nullptr)) {
        DeleteProcThreadAttributeList(attribute_list);
        throw StateError("INTERNAL_ERROR", "Unable to restrict inherited Git process handles.", true);
    }
    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = INVALID_HANDLE_VALUE;
    startup.StartupInfo.hStdOutput = stdout_pipe.write;
    startup.StartupInfo.hStdError = stderr_pipe.write;
    startup.lpAttributeList = attribute_list;
    PROCESS_INFORMATION process{};
    const BOOL created =
        CreateProcessW(git_executable.c_str(), command.data(), nullptr, nullptr, TRUE,
                       EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW, nullptr, nullptr, &startup.StartupInfo,
                       &process);
    DeleteProcThreadAttributeList(attribute_list);
    if (!created) {
        throw StateError("INTERNAL_ERROR", "Unable to start Git.", true);
    }
    CloseHandle(process.hThread);
    CloseHandle(stdout_pipe.write);
    stdout_pipe.write = nullptr;
    CloseHandle(stderr_pipe.write);
    stderr_pipe.write = nullptr;
    try {
        auto drain = std::make_shared<GitArchivePipe::StderrDrain>(stderr_pipe.read);
        stderr_pipe.read = nullptr;
        auto result = GitArchivePipe(process.hProcess, stdout_pipe.read, std::move(drain));
        stdout_pipe.read = nullptr;
        return result;
    } catch (...) {
        TerminateProcess(process.hProcess, ERROR_CANCELLED);
        WaitForSingleObject(process.hProcess, INFINITE);
        CloseHandle(process.hProcess);
        throw;
    }
}
namespace {
bool is_full_sha(std::string_view value) {
    return (value.size() == 40 || value.size() == 64) &&
           std::ranges::all_of(value, [](unsigned char character) { return std::isxdigit(character) != 0; });
}
} // namespace
GitArchivePipe::GitArchivePipe(void *process, void *stdout_read, std::shared_ptr<StderrDrain> stderr_drain) noexcept
    : process_(process), stdout_read_(stdout_read), stderr_drain_(std::move(stderr_drain)) {}
std::size_t GitArchivePipe::read(std::span<std::byte> buffer) {
    if (stdout_read_ == nullptr || buffer.empty()) {
        return 0;
    }
    const auto requested = static_cast<DWORD>((std::min<std::size_t>)(buffer.size(), 1024 * 1024));
    DWORD read = 0;
    if (!ReadFile(static_cast<Handle>(stdout_read_), buffer.data(), requested, &read, nullptr)) {
        if (GetLastError() == ERROR_BROKEN_PIPE) {
            return 0;
        }
        throw StateError("INTERNAL_ERROR", "Unable to read the Git archive pipe.", true);
    }
    return read;
}
int GitArchivePipe::wait() {
    close_handle(stdout_read_);
    if (process_ == nullptr) {
        return 0;
    }
    if (WaitForSingleObject(static_cast<Handle>(process_), INFINITE) != WAIT_OBJECT_0) {
        throw StateError("INTERNAL_ERROR", "Unable to wait for Git.", true);
    }
    DWORD exit_code = 0;
    if (!GetExitCodeProcess(static_cast<Handle>(process_), &exit_code)) {
        throw StateError("INTERNAL_ERROR", "Unable to read the Git exit status.", true);
    }
    close_handle(process_);
    waited_ = true;
    if (stderr_drain_ != nullptr) {
        stderr_drain_->join();
    }
    return static_cast<int>(exit_code);
}
void GitArchivePipe::close() noexcept {
    close_handle(stdout_read_);
    if (process_ != nullptr) {
        if (!waited_) {
            TerminateProcess(static_cast<Handle>(process_), ERROR_CANCELLED);
        }
        WaitForSingleObject(static_cast<Handle>(process_), INFINITE);
        close_handle(process_);
    }
    if (stderr_drain_ != nullptr) {
        stderr_drain_->join();
        stderr_drain_.reset();
    }
    waited_ = true;
}
GitRepository::GitRepository(std::filesystem::path repository)
    : repository_(std::filesystem::absolute(std::move(repository)).lexically_normal()) {}
std::string GitRepository::resolve_commit(std::string_view revision) const {
    if (revision.empty()) {
        throw StateError("COMMIT_NOT_FOUND", "Commit revision is empty.");
    }
    auto process = launch_git({L"git", L"-C", repository_.wstring(), L"rev-parse", L"--verify", L"--end-of-options",
                               std::filesystem::path(std::string(revision) + "^{commit}").wstring()});
    std::array<std::byte, 4096> buffer{};
    std::string output;
    for (std::size_t read = process.read(buffer); read != 0; read = process.read(buffer)) {
        if (output.size() < 8192) {
            const auto retained = (std::min)(read, 8192 - output.size());
            output.append(reinterpret_cast<const char *>(buffer.data()), retained);
        }
    }
    const auto exit_code = process.wait();
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n')) {
        output.pop_back();
    }
    if (exit_code != 0 || !is_full_sha(output)) {
        throw StateError("COMMIT_NOT_FOUND", "Git could not resolve the requested commit.");
    }
    return output;
}
GitArchivePipe GitRepository::open_shader_archive(std::string_view full_sha) const {
    if (!is_full_sha(full_sha)) {
        throw StateError("COMMIT_NOT_FOUND", "Commit archive requires a full commit SHA.");
    }
    return launch_git({L"git", L"-C", repository_.wstring(), L"archive", L"--format=tar",
                       std::filesystem::path(full_sha).wstring(), L"shaders"});
}
} // namespace vibris::mcp