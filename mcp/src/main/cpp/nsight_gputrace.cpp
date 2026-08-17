#include "nsight_gputrace.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <Windows.h>

#include "state_error.hpp"

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

struct GputraceOptions final {
    fs::path ngfx;
    fs::path exe;
    fs::path working_dir;
    fs::path output_dir;
    std::vector<std::string> args;
    std::optional<int> start_after_frames;
    std::optional<int> start_after_ms;
    std::optional<int> max_duration_ms;
    std::optional<int> limit_frames;
    std::optional<std::string> architecture;
    std::optional<int> metric_set_id;
    std::optional<std::string> metric_set_name;
    bool multi_pass = false;
    bool auto_export = true;
    bool dry_run = false;
    int timeout_seconds = 60;
};

std::wstring utf8_to_wide(std::string_view value) {
    if (value.empty()) return {};
    const int count = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (count <= 0) return {};
    std::wstring result(static_cast<std::size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count);
    return result;
}

std::string wide_to_utf8(std::wstring_view value) {
    if (value.empty()) return {};
    const int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
        nullptr, 0, nullptr, nullptr);
    if (count <= 0) return {};
    std::string result(static_cast<std::size_t>(count), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count,
        nullptr, nullptr);
    return result;
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

std::wstring command_line(const std::wstring& executable, const std::vector<std::wstring>& arguments) {
    std::wstring command = quote_argument(executable);
    for (const auto& argument : arguments) {
        command.push_back(L' ');
        command += quote_argument(argument);
    }
    // CreateProcessW rejects command lines over 32767 characters; fail early
    // with a clear error instead of letting the spawn fail obscurely.
    if (command.size() >= 32767) {
        throw StateError("INTERNAL_ERROR", "The Nsight command line is too long.", true);
    }
    return command;
}

std::vector<std::string> string_array(const Json& value, const char* key) {
    std::vector<std::string> result;
    const auto it = value.find(key);
    if (it == value.end() || !it->is_array()) return result;
    for (const auto& item : *it) {
        if (item.is_string()) result.push_back(item.get<std::string>());
    }
    return result;
}

std::optional<int> optional_int(const Json& value, const char* key) {
    const auto it = value.find(key);
    if (it == value.end() || !it->is_number_integer()) return std::nullopt;
    return it->get<int>();
}

std::optional<std::string> optional_string(const Json& value, const char* key) {
    const auto it = value.find(key);
    if (it == value.end() || !it->is_string()) return std::nullopt;
    return it->get<std::string>();
}

bool optional_bool(const Json& value, const char* key, const bool fallback) {
    const auto it = value.find(key);
    if (it == value.end() || !it->is_boolean()) return fallback;
    return it->get<bool>();
}

// Probe for the newest Nsight Graphics ngfx.exe under the standard install
// roots. Returns an empty path when none is found.
fs::path probe_ngfx() {
    auto environment_value = [](const wchar_t* name) {
        std::wstring value(32768, L'\0');
        const DWORD copied = GetEnvironmentVariableW(name, value.data(), static_cast<DWORD>(value.size()));
        if (copied == 0 || copied >= value.size()) return std::wstring{};
        value.resize(copied);
        return value;
    };
    std::vector<fs::path> roots;
    for (const auto* variable : {L"ProgramFiles", L"ProgramFiles(x86)"}) {
        const auto value = environment_value(variable);
        if (!value.empty()) {
            roots.emplace_back(value) /= "NVIDIA Corporation";
        }
    }
    fs::path best;
    std::filesystem::file_time_type best_installed;
    for (const auto& nvidia : roots) {
        std::error_code error;
        for (const auto& entry : fs::directory_iterator(nvidia, error)) {
            if (!entry.is_directory() || entry.path().filename().wstring().find(L"Nsight Graphics") ==
                std::wstring::npos) {
                continue;
            }
            const auto candidate = entry.path() / L"host" / L"windows-desktop-nomad-x64" / L"ngfx.exe";
            if (!fs::is_regular_file(candidate, error) || error) continue;
            // Prefer the most recently installed Nsight Graphics: directory
            // name comparison is unreliable for multi-part versions.
            const auto installed = entry.last_write_time(error);
            if (error || (best.empty() || installed > best_installed)) {
                best = candidate;
                best_installed = installed;
            }
        }
    }
    return best;
}

struct ProcessResult final {
    int exit_code = -1;
    bool timed_out = false;
    std::string output;
};

ProcessResult run_process(const fs::path& executable, const std::vector<std::wstring>& arguments,
    const fs::path& working_dir, const int timeout_seconds) {
    auto stdout_read_handle = static_cast<HANDLE>(nullptr);
    auto stdout_write_handle = static_cast<HANDLE>(nullptr);
    auto stderr_read_handle = static_cast<HANDLE>(nullptr);
    auto stderr_write_handle = static_cast<HANDLE>(nullptr);
    auto process_handle = static_cast<HANDLE>(nullptr);

    SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    const BOOL stdout_created = CreatePipe(&stdout_read_handle, &stdout_write_handle, &security, 1024 * 1024);
    const BOOL stderr_created = CreatePipe(&stderr_read_handle, &stderr_write_handle, &security, 1024 * 1024);
    if (!stdout_created || !stderr_created) {
        // A pipe may have been created before the failure; close every handle
        // that is set so a partial failure does not leak.
        for (HANDLE* handle : {&stdout_read_handle, &stdout_write_handle, &stderr_read_handle,
                               &stderr_write_handle}) {
            if (*handle != nullptr) {
                CloseHandle(*handle);
                *handle = nullptr;
            }
        }
        throw StateError("INTERNAL_ERROR", "Unable to create the Nsight process pipes.", true);
    }
    static_cast<void>(SetHandleInformation(stdout_read_handle, HANDLE_FLAG_INHERIT, 0));
    static_cast<void>(SetHandleInformation(stderr_read_handle, HANDLE_FLAG_INHERIT, 0));

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = INVALID_HANDLE_VALUE;
    startup.hStdOutput = stdout_write_handle;
    startup.hStdError = stderr_write_handle;

    const auto executable_text = executable.wstring();
    const auto working_dir_text = working_dir.empty() ? std::wstring{} : working_dir.wstring();
    auto command = command_line(executable_text, arguments);
    PROCESS_INFORMATION process_info{};
    const auto created = CreateProcessW(executable_text.c_str(), command.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW, nullptr, working_dir_text.empty() ? nullptr : working_dir_text.c_str(),
        &startup, &process_info);
    CloseHandle(stdout_write_handle);
    CloseHandle(stderr_write_handle);
    if (!created) {
        CloseHandle(stdout_read_handle);
        CloseHandle(stderr_read_handle);
        throw StateError("INTERNAL_ERROR", "Unable to start ngfx.exe.", true);
    }
    CloseHandle(process_info.hThread);
    process_handle = process_info.hProcess;

    std::string output;
    output.reserve(64 * 1024);
    constexpr std::size_t max_output_bytes = 256 * 1024;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(timeout_seconds);
    bool timed_out = false;

    // Drain the child's pipes while waiting, mirroring GitRepository::read:
    // a child that fills its stdout/stderr buffer blocks forever, so the
    // pipes must be emptied on every poll or ngfx stalls until the timeout.
    auto drain_pipe = [&](HANDLE pipe) {
        std::array<char, 4096> buffer{};
        for (;;) {
            DWORD available = 0;
            if (!PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr)) {
                if (GetLastError() == ERROR_BROKEN_PIPE) return;
                continue;
            }
            if (available == 0) return;
            const auto requested = static_cast<DWORD>((std::min<std::size_t>)({
                buffer.size(), static_cast<std::size_t>(available),
                max_output_bytes - (std::min)(output.size(), max_output_bytes)}));
            DWORD read = 0;
            if (!ReadFile(pipe, buffer.data(), requested, &read, nullptr)) {
                if (GetLastError() == ERROR_BROKEN_PIPE) return;
                continue;
            }
            if (read == 0) return;
            if (output.size() < max_output_bytes) {
                output.append(buffer.data(), read);
            }
        }
    };

    for (;;) {
        const auto wait = WaitForSingleObject(process_handle, 100);
        drain_pipe(stdout_read_handle);
        drain_pipe(stderr_read_handle);
        if (wait == WAIT_OBJECT_0) break;
        if (std::chrono::steady_clock::now() >= deadline) {
            timed_out = true;
            static_cast<void>(TerminateProcess(process_handle, ERROR_TIMEOUT));
            break;
        }
    }
    drain_pipe(stdout_read_handle);
    drain_pipe(stderr_read_handle);
    DWORD exit_code = 0;
    if (!GetExitCodeProcess(process_handle, &exit_code)) exit_code = 1;
    CloseHandle(process_handle);
    CloseHandle(stdout_read_handle);
    CloseHandle(stderr_read_handle);
    return ProcessResult{static_cast<int>(exit_code), timed_out, std::move(output)};
}

// Newest *.ngfx-gputrace / *.gputrace file in the output directory.
fs::path newest_gputrace(const fs::path& directory) {
    fs::path best;
    std::error_code error;
    if (!fs::is_directory(directory, error)) return best;
    for (const auto& entry : fs::directory_iterator(directory, error)) {
        if (!entry.is_regular_file(error)) continue;
        const auto extension = entry.path().extension().wstring();
        if (extension != L".ngfx-gputrace" && extension != L".gputrace") continue;
        if (best.empty() || fs::last_write_time(entry.path(), error) > fs::last_write_time(best, error)) {
            best = entry.path();
        }
    }
    return best;
}

GputraceOptions options_from_arguments(const Json& arguments) {
    GputraceOptions options;
    if (const auto ngfx = optional_string(arguments, "ngfx_path"); ngfx.has_value()) {
        options.ngfx = *ngfx;
    } else {
        options.ngfx = probe_ngfx();
    }
    if (const auto exe = optional_string(arguments, "exe"); exe.has_value()) {
        options.exe = *exe;
    }
    if (const auto dir = optional_string(arguments, "working_dir"); dir.has_value()) {
        options.working_dir = *dir;
    }
    if (const auto dir = optional_string(arguments, "output_dir"); dir.has_value()) {
        options.output_dir = *dir;
    }
    options.args = string_array(arguments, "args");
    options.start_after_frames = optional_int(arguments, "start_after_frames");
    options.start_after_ms = optional_int(arguments, "start_after_ms");
    options.max_duration_ms = optional_int(arguments, "max_duration_ms");
    options.limit_frames = optional_int(arguments, "limit_frames");
    options.architecture = optional_string(arguments, "architecture");
    options.metric_set_id = optional_int(arguments, "metric_set_id");
    options.metric_set_name = optional_string(arguments, "metric_set_name");
    options.multi_pass = optional_bool(arguments, "multi_pass_metrics", false);
    options.auto_export = optional_bool(arguments, "auto_export", true);
    options.dry_run = optional_bool(arguments, "dry_run", false);
    if (const auto timeout = optional_int(arguments, "timeout_seconds"); timeout.has_value()) {
        options.timeout_seconds = *timeout;
    }
    return options;
}

std::vector<std::wstring> ngfx_arguments(const GputraceOptions& options) {
    std::vector<std::wstring> result{
        L"--activity", L"GPU Trace Profiler",
        L"--platform", L"Windows",
        L"--no-timeout",
        L"--exe", options.exe.wstring(),
    };
    if (!options.working_dir.empty()) {
        result.push_back(L"--dir");
        result.push_back(options.working_dir.wstring());
    }
    if (options.start_after_ms.has_value()) {
        result.push_back(L"--start-after-ms");
        result.push_back(std::to_wstring(*options.start_after_ms));
    } else if (options.start_after_frames.has_value()) {
        result.push_back(L"--start-after-frames");
        result.push_back(std::to_wstring(*options.start_after_frames));
    }
    if (options.max_duration_ms.has_value()) {
        result.push_back(L"--max-duration-ms");
        result.push_back(std::to_wstring(*options.max_duration_ms));
    }
    if (options.limit_frames.has_value()) {
        result.push_back(L"--limit-to-frames");
        result.push_back(std::to_wstring(*options.limit_frames));
    }
    if (options.auto_export) result.push_back(L"--auto-export");
    if (!options.output_dir.empty()) {
        result.push_back(L"--output-dir");
        result.push_back(options.output_dir.wstring());
    }
    if (options.architecture.has_value()) {
        result.push_back(L"--architecture");
        result.push_back(utf8_to_wide(*options.architecture));
    }
    if (options.metric_set_id.has_value()) {
        result.push_back(L"--metric-set-id");
        result.push_back(std::to_wstring(*options.metric_set_id));
    }
    if (options.metric_set_name.has_value()) {
        result.push_back(L"--metric-set-name");
        result.push_back(utf8_to_wide(*options.metric_set_name));
    }
    if (options.multi_pass) result.push_back(L"--multi-pass-metrics");
    if (!options.args.empty()) {
        std::wstring joined;
        for (const auto& argument : options.args) {
            if (!joined.empty()) joined.push_back(L' ');
            joined += quote_argument(utf8_to_wide(argument));
        }
        result.push_back(L"--args");
        result.push_back(std::move(joined));
    }
    return result;
}

} // namespace

ToolOutcome launch_nsight_gputrace(const Json& arguments) {
    auto options = options_from_arguments(arguments);
    if (options.ngfx.empty()) {
        return ToolFailure{"NGFX_NOT_FOUND",
            "Unable to locate ngfx.exe. Provide ngfx_path or install Nsight Graphics.", true,
            Json{{"hint", "Program Files/NVIDIA Corporation/Nsight Graphics <version>/host/windows-desktop-nomad-x64/ngfx.exe"}}};
    }
    if (options.exe.empty()) {
        return ToolFailure{"MISSING_EXE", "The exe (target game executable) is required.", false, Json::object()};
    }
    if (options.dry_run) {
        try {
            const auto command = command_line(options.ngfx.wstring(), ngfx_arguments(options));
            return Json{{"dry_run", true},
                        {"ngfx", options.ngfx.string()},
                        {"command", wide_to_utf8(command)}};
        } catch (const StateError& error) {
            return ToolFailure{std::string(error.code()), std::string(error.what()), error.retryable(),
                               Json::object()};
        }
    }

    if (!options.output_dir.empty()) {
        std::error_code error;
        fs::create_directories(options.output_dir, error);
        if (error) {
            return ToolFailure{"OUTPUT_DIR_ERROR",
                "Unable to create the Nsight output directory: " + options.output_dir.string(), false,
                Json{{"path", options.output_dir.string()}}};
        }
    }
    const auto ngfx_command = ngfx_arguments(options);
    ProcessResult result;
    try {
        result = run_process(options.ngfx, ngfx_command, options.working_dir, options.timeout_seconds);
    } catch (const StateError& error) {
        return ToolFailure{std::string(error.code()), std::string(error.what()), error.retryable(),
                           Json::object()};
    }

    fs::path report;
    if (!options.output_dir.empty()) {
        report = newest_gputrace(options.output_dir);
    }

    Json payload{{"exit_code", result.exit_code},
                 {"timed_out", result.timed_out},
                 {"ngfx", options.ngfx.string()},
                 {"output_tail", result.output.substr((std::min)(result.output.size(), std::size_t{8192}))}};
    if (!report.empty()) payload["report"] = report.string();
    if (result.output.find("NVPA_STATUS_RESOURCE_UNAVAILABLE") != std::string::npos) {
        payload["hint"] = "Another Nsight/profiler session is holding GPU counters. Close it and retry.";
    }
    return payload;
}

} // namespace vibris::mcp
