#pragma once

#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <Windows.h>

#include "state_error.hpp"

#include <filesystem>
#include <string>

namespace vibris::mcp {

inline std::filesystem::path resolve_git_executable() {
    constexpr DWORD max_environment_characters = 32767;
    std::wstring path(max_environment_characters, L'\0');
    const DWORD copied = GetEnvironmentVariableW(L"PATH", path.data(), static_cast<DWORD>(path.size()));
    if (copied == 0 || copied >= path.size()) {
        throw StateError("INTERNAL_ERROR", "Unable to read PATH while locating Git.", true);
    }
    path.resize(copied);

    std::size_t start = 0;
    while (start <= path.size()) {
        const auto end = path.find(L';', start);
        auto entry = path.substr(start, end == std::wstring::npos ? path.size() - start : end - start);
        if (entry.size() >= 2 && entry.front() == L'"' && entry.back() == L'"') {
            entry = entry.substr(1, entry.size() - 2);
        }
        const std::filesystem::path directory(entry);
        std::error_code error;
        const auto candidate = directory / L"git.exe";
        if (directory.is_absolute() && std::filesystem::is_regular_file(candidate, error)) {
            return candidate.lexically_normal();
        }
        if (end == std::wstring::npos) {
            break;
        }
        start = end + 1;
    }
    throw StateError("INTERNAL_ERROR", "Unable to locate git.exe in an absolute PATH entry.", true);
}

} // namespace vibris::mcp