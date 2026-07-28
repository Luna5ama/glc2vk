#include "source_path_policy.hpp"

#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <algorithm>
#include <cwchar>
#include <string_view>
#include <utility>

namespace vibris::mcp {
namespace {

constexpr std::string_view kReparseCode = "SOURCE_CONTAINS_REPARSE_POINT";
constexpr std::string_view kInvalidCharacters = "<>:\"|?*";

[[noreturn]] void reject(std::string message) { throw StateError(kReparseCode, std::move(message)); }

bool same_component(const std::filesystem::path& left, const std::filesystem::path& right) {
    return _wcsicmp(left.c_str(), right.c_str()) == 0;
}

bool ascii_iequals(std::string_view left, std::string_view right) {
    if (left.size() != right.size()) {
        return false;
    }
    for (std::size_t index = 0; index < left.size(); ++index) {
        const auto fold = [](char value) { return value >= 'a' && value <= 'z' ? value - ('a' - 'A') : value; };
        if (fold(left[index]) != fold(right[index])) {
            return false;
        }
    }
    return true;
}

bool is_reserved_device_name(std::string_view component) {
    const auto name = component.substr(0, component.find('.'));
    if (ascii_iequals(name, "CON") || ascii_iequals(name, "PRN") || ascii_iequals(name, "AUX") ||
        ascii_iequals(name, "NUL") || ascii_iequals(name, "CONIN$") || ascii_iequals(name, "CONOUT$")) {
        return true;
    }
    const auto numbered_device = name.size() >= 4 &&
        (ascii_iequals(name.substr(0, 3), "COM") || ascii_iequals(name.substr(0, 3), "LPT"));
    if (!numbered_device) {
        return false;
    }
    if (name.size() == 4) {
        return name.back() >= '1' && name.back() <= '9';
    }
    return name.size() == 5 && static_cast<unsigned char>(name[3]) == 0xC2 &&
        (static_cast<unsigned char>(name[4]) == 0xB9 || static_cast<unsigned char>(name[4]) == 0xB2 ||
            static_cast<unsigned char>(name[4]) == 0xB3);
}

bool is_invalid_component(std::string_view component) {
    if (component.empty() || component == "." || component == ".." || component.back() == '.' ||
        component.back() == ' ' || is_reserved_device_name(component)) {
        return true;
    }
    return component.find_first_of(kInvalidCharacters) != std::string_view::npos ||
        std::ranges::any_of(component, [](unsigned char value) { return value < 32; });
}

std::filesystem::path absolute_lexical(const std::filesystem::path& path) {
    std::error_code error;
    auto absolute = std::filesystem::absolute(path, error);
    if (error) {
        reject("Source path could not be normalized.");
    }
    return absolute.lexically_normal();
}

bool is_within(const std::filesystem::path& root, const std::filesystem::path& candidate) {
    auto root_part = root.begin();
    auto candidate_part = candidate.begin();
    for (; root_part != root.end(); ++root_part, ++candidate_part) {
        if (candidate_part == candidate.end() || !same_component(*root_part, *candidate_part)) {
            return false;
        }
    }
    return true;
}

void reject_if_reparse_point(const std::filesystem::path& path) {
    const auto attributes = GetFileAttributesW(path.c_str());
    if (attributes != INVALID_FILE_ATTRIBUTES) {
        if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            reject("Source path contains a Windows reparse point.");
        }
        return;
    }

    const auto error = GetLastError();
    if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) {
        reject("Source path attributes could not be inspected.");
    }
}

} // namespace

std::filesystem::path SourcePathPolicy::archive_relative_path(const SourceEntry& entry) const {
    if (entry.kind != SourceEntryKind::regular_file && entry.kind != SourceEntryKind::directory) {
        reject("Archive entry is not a regular file or directory.");
    }
    if (entry.has_reparse_point) {
        reject("Archive entry has the Windows reparse-point attribute.");
    }
    if (entry.path.empty() || entry.path.find('\\') != std::string::npos ||
        entry.path.find('\0') != std::string::npos) {
        reject("Archive entry has an invalid path.");
    }

    constexpr std::string_view prefix = "shaders/";
    std::string_view path = entry.path;
    if (!path.starts_with(prefix)) {
        reject("Archive entry is outside the shaders directory.");
    }
    path.remove_prefix(prefix.size());
    if (path.empty()) {
        if (entry.kind != SourceEntryKind::directory) {
            reject("Regular archive entry resolves to the shaders directory.");
        }
        return {};
    }
    if (path.ends_with('/')) {
        if (entry.kind != SourceEntryKind::directory) {
            reject("Regular archive entry ends with a directory separator.");
        }
        path.remove_suffix(1);
        if (path.empty() || path.ends_with('/')) {
            reject("Archive directory entry has an invalid trailing separator.");
        }
    }

    std::filesystem::path relative;
    while (!path.empty()) {
        const auto separator = path.find('/');
        const auto component = path.substr(0, separator);
        if (is_invalid_component(component)) {
            reject("Archive entry has a Win32-invalid path component.");
        }
        try {
            relative /= std::filesystem::path(component);
        } catch (const std::filesystem::filesystem_error&) {
            reject("Archive entry path encoding is invalid.");
        }
        if (separator == std::string_view::npos) {
            break;
        }
        path.remove_prefix(separator + 1);
    }
    if (relative.empty() || relative.has_root_name() || relative.has_root_directory() || relative.is_absolute()) {
        reject("Archive entry does not resolve to a safe relative path.");
    }
    return relative;
}

void SourcePathPolicy::require_no_reparse_ancestry(const std::filesystem::path& root,
                                                   const std::filesystem::path& candidate) const {
    const auto absolute_root = absolute_lexical(root);
    const auto absolute_candidate = absolute_lexical(candidate.is_absolute() ? candidate : root / candidate);
    if (!is_within(absolute_root, absolute_candidate)) {
        reject("Source path escapes its declared root.");
    }

    auto current = absolute_candidate;
    while (!current.empty()) {
        reject_if_reparse_point(current);
        if (current == current.root_path()) {
            break;
        }
        const auto parent = current.parent_path();
        if (parent == current) {
            break;
        }
        current = parent;
    }
}

} // namespace vibris::mcp