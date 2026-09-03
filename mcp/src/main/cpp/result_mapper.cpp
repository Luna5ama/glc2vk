#include "result_mapper.hpp"

#include "git_repository.hpp"
#include "source_preparer.hpp"

#include <google/protobuf/descriptor.h>
#include <google/protobuf/message.h>
#include <google/protobuf/util/json_util.h>
#include <nlohmann/json.hpp>

#include <stdexcept>
#include <string>

namespace vibris::mcp {
namespace proto = ::vibris::control::v2;
namespace {

void normalize_integer_scalars(const google::protobuf::Message& message, nlohmann::json& mapped);

std::string map_key(const google::protobuf::Message& entry,
    const google::protobuf::FieldDescriptor& key) {
    const auto* reflection = entry.GetReflection();
    switch (key.cpp_type()) {
    case google::protobuf::FieldDescriptor::CPPTYPE_BOOL:
        return reflection->GetBool(entry, &key) ? "true" : "false";
    case google::protobuf::FieldDescriptor::CPPTYPE_INT32:
        return std::to_string(reflection->GetInt32(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
        return std::to_string(reflection->GetInt64(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT32:
        return std::to_string(reflection->GetUInt32(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT64:
        return std::to_string(reflection->GetUInt64(entry, &key));
    case google::protobuf::FieldDescriptor::CPPTYPE_STRING:
        return reflection->GetString(entry, &key);
    default:
        throw std::runtime_error("protobuf JSON map key type is unsupported");
    }
}

void normalize_field(const google::protobuf::Message& message,
    const google::protobuf::FieldDescriptor& field, nlohmann::json& target, const int repeated_index) {
    const auto* reflection = message.GetReflection();
    switch (field.cpp_type()) {
    case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
        target = repeated_index < 0
            ? reflection->GetInt64(message, &field)
            : reflection->GetRepeatedInt64(message, &field, repeated_index);
        break;
    case google::protobuf::FieldDescriptor::CPPTYPE_UINT64:
        target = repeated_index < 0
            ? reflection->GetUInt64(message, &field)
            : reflection->GetRepeatedUInt64(message, &field, repeated_index);
        break;
    case google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE:
        normalize_integer_scalars(repeated_index < 0
                ? reflection->GetMessage(message, &field)
                : reflection->GetRepeatedMessage(message, &field, repeated_index),
            target);
        break;
    default:
        break;
    }
}

void normalize_integer_scalars(const google::protobuf::Message& message, nlohmann::json& mapped) {
    const auto* descriptor = message.GetDescriptor();
    const auto* reflection = message.GetReflection();
    for (int field_index = 0; field_index < descriptor->field_count(); ++field_index) {
        const auto* field = descriptor->field(field_index);
        const auto mapped_field = mapped.find(field->name());
        if (mapped_field == mapped.end()) continue;

        if (field->is_map()) {
            if (!mapped_field->is_object()) {
                throw std::runtime_error("protobuf JSON map-field mapping is inconsistent");
            }
            for (int index = 0; index < reflection->FieldSize(message, field); ++index) {
                const auto& entry = reflection->GetRepeatedMessage(message, field, index);
                const auto* entry_descriptor = entry.GetDescriptor();
                const auto* key_field = entry_descriptor->FindFieldByName("key");
                const auto* value_field = entry_descriptor->FindFieldByName("value");
                if (key_field == nullptr || value_field == nullptr) {
                    throw std::runtime_error("protobuf map entry descriptor is incomplete");
                }
                const auto key = map_key(entry, *key_field);
                const auto value = mapped_field->find(key);
                if (value == mapped_field->end()) {
                    throw std::runtime_error("protobuf JSON map entry is missing");
                }
                normalize_field(entry, *value_field, *value, -1);
            }
            continue;
        }

        if (field->is_repeated()) {
            if (!mapped_field->is_array() ||
                mapped_field->size() != static_cast<std::size_t>(reflection->FieldSize(message, field))) {
                throw std::runtime_error("protobuf JSON repeated-field mapping is inconsistent");
            }
            for (std::size_t index = 0; index < mapped_field->size(); ++index) {
                normalize_field(message, *field, mapped_field->at(index), static_cast<int>(index));
            }
        } else {
            normalize_field(message, *field, *mapped_field, -1);
        }
    }
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

void hide_operational_runtime_internals(nlohmann::json& mapped) {
    auto status = mapped.find("status");
    if (status == mapped.end() || !status->is_object()) return;
    const auto readiness = status->find("readiness");
    if (readiness == status->end() || !readiness->is_object() ||
        !readiness->value("core_online", false) ||
        !readiness->value("minecraft_connected", false)) {
        return;
    }

    // These fields describe internal lease and shader-load transitions, not whether
    // an agent may submit work. Exposing them makes a healthy, queueable server look
    // broken while Minecraft is compiling a shader pack. Core remains authoritative;
    // MCP only removes the transient diagnostics from the agent-facing projection.
    (*status)["operational"] = true;
    const auto last_error = status->find("last_error");
    const bool restart_launch_failed = last_error != status->end() && last_error->is_object() &&
        last_error->value("message", std::string{}).starts_with(
            "Scheduled Minecraft restart could not be launched:");
    status->erase("state");
    status->erase("can_start_job");
    status->erase("readiness");
    status->erase("active_lease");
    status->erase("transitions");
    if (!restart_launch_failed) status->erase("last_error");
}

}

nlohmann::json ResultMapper::message(const google::protobuf::Message& value) {
    google::protobuf::util::JsonPrintOptions options;
    options.preserve_proto_field_names = true;
    options.always_print_fields_with_no_presence = true;

    std::string encoded;
    const auto status = google::protobuf::util::MessageToJsonString(value, &encoded, options);
    if (!status.ok()) {
        throw std::runtime_error("protobuf JSON mapping failed: " + status.ToString());
    }
    auto mapped = nlohmann::json::parse(encoded);
    normalize_integer_scalars(value, mapped);
    return mapped;
}

nlohmann::json ResultMapper::list_presets(const proto::ListPresetsResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::list_resources(const proto::ListResourcesResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::validation(const proto::ValidateContextResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::status(const proto::GetStatusResponse& response) {
    auto mapped = message(response);
    hide_operational_runtime_internals(mapped);
    return mapped;
}

nlohmann::json ResultMapper::artifacts(const proto::ManageArtifactsResponse& response) {
    return message(response);
}

nlohmann::json ResultMapper::server_message(const proto::ServerMessage& response) {
    return message(response);
}

void ResultMapper::finalize_provenance(nlohmann::json& result) {
    finalize_all(result);
}

}
