#include "workspace_artifact_link.hpp"

#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <algorithm>
#include <cwctype>
#include <system_error>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

[[noreturn]] void artifact_link_error(std::string message) {
    throw StateError(kArtifactLinkErrorCode, std::move(message));
}

bool equal_component(const fs::path& left, const fs::path& right) {
    const auto& lhs = left.native();
    const auto& rhs = right.native();
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin(),
        [](wchar_t a, wchar_t b) { return std::towlower(a) == std::towlower(b); });
}

bool equal_path(const fs::path& left, const fs::path& right) {
    const auto lhs = left.lexically_normal();
    const auto rhs = right.lexically_normal();
    auto lhs_component = lhs.begin();
    auto rhs_component = rhs.begin();
    for (; lhs_component != lhs.end() && rhs_component != rhs.end(); ++lhs_component, ++rhs_component) {
        if (!equal_component(*lhs_component, *rhs_component)) return false;
    }
    return lhs_component == lhs.end() && rhs_component == rhs.end();
}

bool is_reparse_point(const fs::path& path) {
    const auto attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
}

DWORD checked_attributes(const fs::path& path, std::string_view unavailable) {
    const auto attributes = GetFileAttributesW(path.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES) artifact_link_error(std::string(unavailable));
    return attributes;
}

void require_ordinary_path(const fs::path& path, bool directory, std::string_view unavailable) {
    auto current = path.root_path();
    for (const auto& component : path.relative_path()) {
        current /= component;
        const auto attributes = checked_attributes(current, unavailable);
        if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            artifact_link_error("A returned artifact path contains a reparse point.");
        }
    }
    const auto attributes = checked_attributes(path, unavailable);
    if (((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) != directory) {
        artifact_link_error(std::string(unavailable));
    }
}

bool link_targets(const fs::path& link, const fs::path& target) {
    std::error_code error;
    auto current = fs::read_symlink(link, error);
    if (error) return false;
    if (current.is_relative()) current = link.parent_path() / current;
    return equal_path(current, target);
}

void ensure_link(const fs::path& link, const fs::path& target) {
    std::error_code error;
    const auto status = fs::symlink_status(link, error);
    if (error && error != std::errc::no_such_file_or_directory) {
        artifact_link_error("Unable to inspect .vibris/artifact.");
    }
    const bool missing = error == std::errc::no_such_file_or_directory || status.type() == fs::file_type::not_found;
    if (!missing) {
        if (!is_reparse_point(link)) {
            artifact_link_error(".vibris/artifact is occupied by a non-link entry.");
        }
        if (link_targets(link, target)) return;
        error.clear();
        if (!fs::remove(link, error) || error) {
            error.clear();
            const auto current = fs::symlink_status(link, error);
            if (!error && current.type() != fs::file_type::not_found && is_reparse_point(link) &&
                link_targets(link, target)) {
                return;
            }
            if (error != std::errc::no_such_file_or_directory && current.type() != fs::file_type::not_found) {
                artifact_link_error("Unable to replace the invalid .vibris/artifact link.");
            }
        }
    }

    error.clear();
    fs::create_directory_symlink(target, link, error);
    if (!error) return;

    // Another MCP process may have published the same link first.
    if (is_reparse_point(link) && link_targets(link, target)) return;
    artifact_link_error("Unable to create .vibris/artifact as a directory link.");
}

void collect_paths(Json& value, std::vector<Json*>& paths) {
    if (!value.is_object()) return;
    const auto collect_string = [&paths](Json& object, std::string_view key) {
        const auto found = object.find(key);
        if (found != object.end() && found->is_string() && !found->get_ref<const std::string&>().empty()) {
            paths.push_back(&*found);
        }
    };
    const auto collect_artifacts = [&paths](Json& object, std::string_view collection, std::string_view field) {
        const auto found = object.find(collection);
        if (found == object.end() || !found->is_array()) return;
        for (auto& artifact : *found) {
            if (!artifact.is_object()) continue;
            const auto path = artifact.find(field);
            if (path != artifact.end() && path->is_string() && !path->get_ref<const std::string&>().empty()) {
                paths.push_back(&*path);
            }
        }
    };

    collect_string(value, "manifest_path");
    collect_string(value, "log_path");
    collect_artifacts(value, "artifacts", "path");
    collect_artifacts(value, "files", "relative_path");
    const auto manifests = value.find("manifests");
    if (manifests != value.end() && manifests->is_array()) {
        for (auto& manifest : *manifests) collect_paths(manifest, paths);
    }
    const auto manifest = value.find("manifest");
    if (manifest != value.end() && manifest->is_object()) collect_paths(*manifest, paths);
    const auto diagnostics = value.find("diagnostics");
    if (diagnostics != value.end() && diagnostics->is_array()) {
        for (auto& diagnostic : *diagnostics) collect_string(diagnostic, "log_path");
    }
}

fs::path artifact_workspace_directory(const std::vector<Json*>& paths, std::string_view workspace_id) {
    const auto first = fs::path(paths.front()->get_ref<const std::string&>()).lexically_normal();
    if (!first.is_absolute() || first.parent_path().empty() ||
        first.parent_path().parent_path().empty() || first.parent_path().parent_path().parent_path().empty()) {
        artifact_link_error("The server returned an invalid artifact path.");
    }
    const auto candidate = first.parent_path().parent_path().parent_path();
    if (!equal_component(candidate.filename(), fs::path(workspace_id))) {
        artifact_link_error("The server artifact path does not belong to this workspace ID.");
    }
    require_ordinary_path(candidate, true, "The server artifact workspace directory is unavailable.");
    return candidate;
}

fs::path relative_artifact_path(const fs::path& path, const fs::path& target) {
    if (!path.is_absolute()) artifact_link_error("The server returned a non-absolute artifact path.");
    const auto normalized = path.lexically_normal();
    auto path_component = normalized.begin();
    for (auto target_component = target.begin(); target_component != target.end(); ++target_component) {
        if (path_component == normalized.end() || !equal_component(*path_component, *target_component)) {
            artifact_link_error("A returned artifact path is outside the workspace artifact directory.");
        }
        ++path_component;
    }
    fs::path relative;
    for (; path_component != normalized.end(); ++path_component) relative /= *path_component;
    if (relative.empty()) artifact_link_error("A returned artifact path is outside the workspace artifact directory.");
    require_ordinary_path(normalized, false, "A returned artifact path is unavailable.");
    return relative;
}

} // namespace

WorkspaceArtifactLink::WorkspaceArtifactLink(fs::path workspace_root, std::string workspace_id)
    : workspace_root_(std::move(workspace_root)),
      link_path_(workspace_root_ / ".vibris" / "artifact"),
      workspace_id_(std::move(workspace_id)) {
}

void WorkspaceArtifactLink::rewrite(ToolOutcome& outcome) const {
    Json* payload = nullptr;
    if (auto* result = std::get_if<Json>(&outcome)) {
        payload = result;
    } else if (auto* failure = std::get_if<ToolFailure>(&outcome)) {
        payload = &failure->details;
    }
    if (payload == nullptr) return;

    std::vector<Json*> paths;
    collect_paths(*payload, paths);
    if (paths.empty()) return;

    const auto target = artifact_workspace_directory(paths, workspace_id_);
    std::vector<fs::path> relative_paths;
    relative_paths.reserve(paths.size());
    for (const auto* path : paths) {
        relative_paths.push_back(relative_artifact_path(
            fs::path(path->get_ref<const std::string&>()), target));
    }
    ensure_link(link_path_, target);
    for (std::size_t index = 0; index < paths.size(); ++index) {
        *paths[index] = (link_path_ / relative_paths[index]).string();
    }
}

} // namespace vibris::mcp
