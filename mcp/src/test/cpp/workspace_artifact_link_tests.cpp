#include "state_error.hpp"
#include "workspace_artifact_link.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace {

namespace fs = std::filesystem;

using vibris::mcp::Json;
using vibris::mcp::StateError;
using vibris::mcp::ToolOutcome;
using vibris::mcp::WorkspaceArtifactLink;

void require(const bool condition, const std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

class TempTree final {
public:
    explicit TempTree(const std::string_view label)
        : path_(fs::temp_directory_path() /
              ("vibris-artifact-link-" + std::string(label) + "-" + std::to_string(
                  std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_);
    }

    TempTree(const TempTree&) = delete;
    TempTree& operator=(const TempTree&) = delete;

    ~TempTree() {
        std::error_code error;
        fs::remove_all(path_, error);
    }

    [[nodiscard]] const fs::path& path() const noexcept {
        return path_;
    }

private:
    fs::path path_;
};

void write_file(const fs::path& path, const std::string_view contents = "artifact") {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(contents.data(), static_cast<std::streamsize>(contents.size()));
    if (!output) throw std::runtime_error("unable to create artifact-link fixture file");
}

void collect_user_paths(const Json& value, std::vector<std::string>& paths) {
    if (value.is_array()) {
        for (const auto& item : value) collect_user_paths(item, paths);
        return;
    }
    if (!value.is_object()) return;
    for (const auto& [key, item] : value.items()) {
        if ((key == "relative_path" || key == "manifest_path" || key == "log_path") && item.is_string()) {
            paths.push_back(item.get<std::string>());
        }
        collect_user_paths(item, paths);
    }
}

void require_artifact_link_error(const WorkspaceArtifactLink& link, ToolOutcome& outcome) {
    try {
        link.rewrite(outcome);
    } catch (const StateError& error) {
        require(error.code() == vibris::mcp::kArtifactLinkErrorCode,
            "unsafe artifact path returned the wrong error code");
        return;
    }
    throw std::runtime_error("unsafe artifact path did not fail closed");
}

void recursively_localizes_strict_v2_paths() {
    TempTree tree("recursive");
    const auto workspace_root = tree.path() / "worktree";
    const auto artifact_root = tree.path() / "server" / "artifacts" / "workspace-id";
    const auto request_root = artifact_root / "job-id" / "request-id";
    const auto screenshot = request_root / "screenshot.png";
    const auto manifest = request_root / "manifest.json";
    const auto log = request_root / "shader.log";
    const auto prelude = request_root / "prelude.bin";
    const auto nested = request_root / "nested.bin";
    const auto matrix = request_root / "matrix.bin";
    for (const auto& file : {screenshot, manifest, log, prelude, nested, matrix}) write_file(file);
    fs::create_directories(workspace_root);

    ToolOutcome outcome = Json{{"result", {
        {"artifacts", Json::array({{{"relative_path", screenshot.string()}}})},
        {"manifest", {{"manifest_path", manifest.string()}}},
        {"diagnostics", Json::array({{{"log_path", log.string()}}})},
        {"prelude_receipts", Json::array({{{"capture", {{"artifacts",
            Json::array({{{"relative_path", prelude.string()}}})}}}}})},
        {"action_receipts", Json::array({{{"capture", {{"artifacts",
            Json::array({{{"relative_path", nested.string()}}})}}}}})},
        {"matrix", {{"cases", Json::array({{{"artifacts",
            Json::array({{{"relative_path", matrix.string()}}})}}})}}},
    }}};

    WorkspaceArtifactLink(workspace_root, "workspace-id").rewrite(outcome);
    const auto& result = std::get<Json>(outcome);
    std::vector<std::string> paths;
    collect_user_paths(result, paths);
    require(paths.size() == 6, "recursive artifact-link fixture lost a strict-v2 path field");
    const auto link = (workspace_root / ".vibris" / "artifact").lexically_normal();
    for (const auto& value : paths) {
        const auto path = fs::path(value).lexically_normal();
        require(path.is_absolute(), "localized artifact path is not absolute");
        const auto relative = path.lexically_relative(link);
        require(!relative.empty() && *relative.begin() != "..",
            "localized artifact path escaped the worktree artifact link");
        require(value.find((tree.path() / "server").string()) == std::string::npos,
            "localized outcome leaked the backing artifact root");
    }
    require(fs::is_directory(link), "worktree artifact link was not created or is not usable");
}

void rejects_before_partial_rewrite() {
    TempTree tree("atomic");
    const auto workspace_root = tree.path() / "worktree";
    const auto request_root = tree.path() / "server" / "artifacts" / "workspace-id" / "job-id" / "request-id";
    const auto valid = request_root / "valid.bin";
    const auto missing = request_root / "missing.bin";
    write_file(valid);
    fs::create_directories(workspace_root);
    ToolOutcome outcome = Json{{"first", {{"relative_path", valid.string()}}},
        {"second", {{"relative_path", missing.string()}}}};
    const auto original = std::get<Json>(outcome);

    const WorkspaceArtifactLink link(workspace_root, "workspace-id");
    require_artifact_link_error(link, outcome);
    require(std::get<Json>(outcome) == original, "invalid target caused a partial path rewrite");
    require(!fs::exists(workspace_root / ".vibris" / "artifact"),
        "invalid target created the worktree artifact link before validation completed");

    const auto other = tree.path() / "server" / "artifacts" / "other-workspace" / "job-id" /
        "request-id" / "other.bin";
    write_file(other);
    outcome = Json{{"first", {{"relative_path", valid.string()}}},
        {"second", {{"relative_path", other.string()}}}};
    const auto cross_workspace_original = std::get<Json>(outcome);
    require_artifact_link_error(link, outcome);
    require(std::get<Json>(outcome) == cross_workspace_original,
        "cross-workspace target caused a partial path rewrite");
}

void rejects_reparse_and_nonordinary_targets() {
    {
        TempTree tree("directory");
        const auto workspace_root = tree.path() / "worktree";
        const auto directory = tree.path() / "server" / "artifacts" / "workspace-id" / "job-id" /
            "request-id" / "directory";
        fs::create_directories(directory);
        fs::create_directories(workspace_root);
        ToolOutcome outcome = Json{{"relative_path", directory.string()}};
        require_artifact_link_error(WorkspaceArtifactLink(workspace_root, "workspace-id"), outcome);
    }
    {
        TempTree tree("reparse");
        const auto workspace_root = tree.path() / "worktree";
        const auto server = tree.path() / "server" / "artifacts";
        const auto actual_workspace = tree.path() / "actual-workspace";
        const auto linked_workspace = server / "workspace-id";
        const auto artifact = linked_workspace / "job-id" / "request-id" / "artifact.bin";
        fs::create_directories(server);
        fs::create_directories(actual_workspace);
        std::error_code link_error;
        fs::create_directory_symlink(actual_workspace, linked_workspace, link_error);
        require(!link_error, "unable to create the reparse-point artifact fixture");
        write_file(artifact);
        fs::create_directories(workspace_root);
        ToolOutcome outcome = Json{{"relative_path", artifact.string()}};
        require_artifact_link_error(WorkspaceArtifactLink(workspace_root, "workspace-id"), outcome);
    }
}

void run(const std::string_view scenario) {
    if (scenario == "ArtifactLinkRecursivelyLocalizesStrictV2Paths") {
        return recursively_localizes_strict_v2_paths();
    }
    if (scenario == "ArtifactLinkRejectsBeforePartialRewrite") return rejects_before_partial_rewrite();
    if (scenario == "ArtifactLinkRejectsUnsafeTargets") return rejects_reparse_and_nonordinary_targets();
    if (scenario == "all") {
        recursively_localizes_strict_v2_paths();
        rejects_before_partial_rewrite();
        rejects_reparse_and_nonordinary_targets();
        return;
    }
    throw std::invalid_argument("unknown scenario");
}

} // namespace

int main(const int argc, char** argv) {
    const std::string scenario = argc > 1 ? argv[1] : "all";
    try {
        run(scenario);
        std::cout << "PASS " << scenario << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << scenario << ": " << error.what() << '\n';
        return 1;
    }
}
