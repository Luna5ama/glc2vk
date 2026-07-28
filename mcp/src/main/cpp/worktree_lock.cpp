#include "worktree_lock.hpp"

#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <cstdint>
#include <filesystem>
#include <limits>
#include <string>
#include <utility>

namespace vibris::mcp {
namespace {

std::wstring lowercase_path(const std::filesystem::path& canonical_root) {
    auto value = canonical_root.native();
    if (value.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        throw StateError(kInvalidWorktreeCode, "Workspace root path is too long.");
    }
    const auto required = LCMapStringEx(
        LOCALE_NAME_INVARIANT,
        LCMAP_LOWERCASE,
        value.data(),
        static_cast<int>(value.size()),
        nullptr,
        0,
        nullptr,
        nullptr,
        0);
    if (required == 0) {
        throw StateError(kStateIoErrorCode, "Unable to normalize the worktree lock name.", true);
    }
    std::wstring lowered(static_cast<std::size_t>(required), L'\0');
    if (LCMapStringEx(
            LOCALE_NAME_INVARIANT,
            LCMAP_LOWERCASE,
            value.data(),
            static_cast<int>(value.size()),
            lowered.data(),
            required,
            nullptr,
            nullptr,
            0) == 0) {
        throw StateError(kStateIoErrorCode, "Unable to normalize the worktree lock name.", true);
    }
    return lowered;
}

std::wstring mutex_name(const std::filesystem::path& root) {
    std::error_code error;
    const auto canonical_root = std::filesystem::canonical(root, error);
    if (error || !std::filesystem::is_directory(canonical_root, error) || error) {
        throw StateError(kInvalidWorktreeCode, "Worktree lock root must be an existing directory.");
    }

    std::uint64_t hash = 14695981039346656037ull;
    for (const auto character : lowercase_path(canonical_root)) {
        const auto code_unit = static_cast<std::uint16_t>(character);
        hash = (hash ^ static_cast<std::uint8_t>(code_unit)) * 1099511628211ull;
        hash = (hash ^ static_cast<std::uint8_t>(code_unit >> 8)) * 1099511628211ull;
    }

    constexpr wchar_t hex[] = L"0123456789abcdef";
    std::wstring suffix(16, L'0');
    for (auto& digit : suffix) {
        digit = hex[(hash >> 60) & 0x0f];
        hash <<= 4;
    }
    return L"Local\\Vibris.Mcp.Worktree." + suffix;
}

} // namespace

WorktreeLock::WorktreeLock(void* handle) noexcept : handle_(handle) {
}

WorktreeLock::WorktreeLock(WorktreeLock&& other) noexcept : handle_(std::exchange(other.handle_, nullptr)) {
}

WorktreeLock& WorktreeLock::operator=(WorktreeLock&& other) noexcept {
    if (this != &other) {
        if (handle_) {
            CloseHandle(static_cast<HANDLE>(handle_));
        }
        handle_ = std::exchange(other.handle_, nullptr);
    }
    return *this;
}

WorktreeLock::~WorktreeLock() {
    if (handle_) {
        CloseHandle(static_cast<HANDLE>(handle_));
    }
}

WorktreeLock WorktreeLock::acquire(const std::filesystem::path& canonical_root) {
    const auto name = mutex_name(canonical_root);
    const auto handle = CreateMutexW(nullptr, FALSE, name.c_str());
    if (!handle) {
        throw StateError(kStateIoErrorCode, "Unable to create the worktree mutex.", true);
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        CloseHandle(handle);
        throw StateError(kWorktreeAlreadyOwnedCode, "Another MCP process already owns this worktree.");
    }
    return WorktreeLock(handle);
}

} // namespace vibris::mcp