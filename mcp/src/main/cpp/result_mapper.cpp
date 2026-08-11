#include "result_mapper.hpp"

#include "git_repository.hpp"
#include "source_preparer.hpp"

#include <google/protobuf/message.h>
#include <google/protobuf/util/json_util.h>
#include <nlohmann/json.hpp>

#include <stdexcept>
#include <string>

namespace vibris::mcp {
namespace proto = ::vibris::control::v2;
namespace {

nlohmann::json map_message(const google::protobuf::Message& message) {
    google::protobuf::util::JsonPrintOptions options;
    options.preserve_proto_field_names = true;
    options.always_print_fields_with_no_presence = true;

    std::string encoded;
    const auto status = google::protobuf::util::MessageToJsonString(message, &encoded, options);
    if (!status.ok()) {
        throw std::runtime_error("protobuf JSON mapping failed: " + status.ToString());
    }
    return nlohmann::json::parse(encoded);
}

void finalize_one(nlohmann::json& provenance) {
    if (!provenance.is_object()) return;
    const auto measured = provenance.value("source_snapshot_sha256", std::string{});
    const auto start_head = provenance.value("start_head", std::string{});
    if (measured.empty() || start_head.empty()) return;
    const auto worktree = provenance.value("worktree_root", std::string{});
    if (worktree.empty()) {
        provenance["completion_head"] = start_head;
        provenance["head_changed"] = false;
        provenance["stale"] = false;
        return;
    }
    if (provenance.value("requested_revision", std::string{}) != "workspace") {
        const auto completion_head = GitRepository(std::filesystem::path(worktree)).resolve_commit("HEAD");
        provenance["completion_head"] = completion_head;
        provenance["head_changed"] = completion_head != start_head;
        provenance["stale"] = false;
        return;
    }
    const auto completion = capture_workspace_provenance(
        std::filesystem::path(worktree),
        {.max_total_bytes = 512ULL * 1024ULL * 1024ULL, .max_files = 100'000});
    provenance["completion_head"] = completion.head;
    provenance["head_changed"] = completion.head != start_head;
    const auto delta = shader_content_delta_sha256(measured, completion.source_snapshot_sha256);
    provenance["stale"] = !delta.empty();
    if (!delta.empty()) provenance["dirty_shader_delta_sha256"] = delta;
}

void finalize_all(nlohmann::json& value) {
    if (value.is_array()) {
        for (auto& item : value) finalize_all(item);
        return;
    }
    if (!value.is_object()) return;
    for (auto& [key, item] : value.items()) {
        if (key == "provenance" || key == "baseline_provenance" || key == "candidate_provenance") {
            finalize_one(item);
        }
        finalize_all(item);
    }
}

}

nlohmann::json ResultMapper::list_presets(const proto::ListPresetsResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::list_resources(const proto::ListResourcesResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::validation(const proto::ValidateContextResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::status(const proto::GetStatusResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::artifacts(const proto::ManageArtifactsResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::server_message(const proto::ServerMessage& response) {
    return map_message(response);
}

void ResultMapper::finalize_provenance(nlohmann::json& result) {
    finalize_all(result);
}

}
