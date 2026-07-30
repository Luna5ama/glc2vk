#include "job_protocol.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <utility>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v1;
constexpr std::uint64_t queue_timeout_ms = 60'000;
constexpr std::uint64_t execution_timeout_ms = 120'000;
constexpr std::uint64_t total_timeout_ms = 180'000;

proto::ArtifactFormat format(std::string_view value) {
    if (value == "png") return proto::ARTIFACT_FORMAT_PNG;
    if (value == "raw") return proto::ARTIFACT_FORMAT_RAW;
    if (value == "bin") return proto::ARTIFACT_FORMAT_BIN;
    throw std::invalid_argument("unsupported artifact format");
}

std::string config_value(const Json& value) {
    return value.is_string() ? value.get<std::string>() : value.dump();
}

std::string short_name(std::string value, std::string_view prefix) {
    if (value.starts_with(prefix)) value.erase(0, prefix.size());
    std::ranges::transform(value, value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

void require_sources(std::span<const proto::PreparedSourceRef> sources, std::size_t expected) {
    if (sources.size() != expected) throw std::invalid_argument("prepared source count does not match execution");
}

void scene(const SessionConfig& config, const proto::SceneContext& scene_context, proto::SubmitJob& job) {
    job.set_workspace_id(config.workspace_id);
    job.mutable_context()->CopyFrom(scene_context);
    job.mutable_context()->set_fov(config.fov);
    auto* timeouts = job.mutable_timeouts();
    timeouts->set_queue_timeout_ms(queue_timeout_ms);
    timeouts->set_execution_timeout_ms(execution_timeout_ms);
    timeouts->set_total_timeout_ms(total_timeout_ms);
}

void shader_config(const Json& arguments, proto::SubmitJob& job) {
    if (!arguments.contains("config")) return;
    for (const auto& [key, value] : arguments.at("config").items()) {
        (*job.mutable_shader_config()->mutable_values())[key] = config_value(value);
    }
}

void reload_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::RecipeSpec& recipe) {
    require_sources(sources, 1);
    auto* value = recipe.mutable_reload_and_capture();
    value->set_source_uuid(sources.front().uuid());
    value->set_warmup_frames(arguments.value("warmup_frames", config.default_warmup_frames));
    value->set_screenshot_format(format(arguments.value("screenshot_format", std::string("png"))));
}

void debug_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::RecipeSpec& recipe) {
    require_sources(sources, 1);
    auto* value = recipe.mutable_capture_debug_bundle();
    value->set_source_uuid(sources.front().uuid());
    value->set_warmup_frames(arguments.value("warmup_frames", config.default_warmup_frames));
    value->set_screenshot(arguments.value("screenshot", false));
    for (const auto& texture : arguments.value("textures", Json::array())) {
        value->add_textures(texture.get<std::string>());
    }
    for (const auto& buffer : arguments.value("buffers", Json::array())) {
        value->add_buffers(buffer.get<std::string>());
    }
}

void ab_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::RecipeSpec& recipe) {
    require_sources(sources, 2);
    auto* value = recipe.mutable_ab_compare();
    value->mutable_baseline()->set_label(arguments.at("a").at("label").get<std::string>());
    value->mutable_baseline()->set_source_uuid(sources[0].uuid());
    value->mutable_candidate()->set_label(arguments.at("b").at("label").get<std::string>());
    value->mutable_candidate()->set_source_uuid(sources[1].uuid());
    value->set_warmup_frames(arguments.value("warmup_frames", config.default_warmup_frames));
    for (const auto& capture : arguments.at("captures")) {
        auto* target = value->add_captures();
        const auto type = capture.at("type").get<std::string>();
        if (type == "screenshot") target->set_kind(proto::CAPTURE_TARGET_KIND_SCREENSHOT);
        if (type == "texture") target->set_kind(proto::CAPTURE_TARGET_KIND_TEXTURE);
        if (type == "buffer") target->set_kind(proto::CAPTURE_TARGET_KIND_BUFFER);
        target->set_name(capture.value("name", std::string{}));
        const auto default_format = type == "buffer" ? std::string("bin") : std::string("png");
        target->set_format(format(capture.value("format", default_format)));
    }
}

void recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::SubmitJob& job) {
    const auto kind = arguments.at("recipe").get<std::string>();
    if (kind == "reload_and_capture") return reload_recipe(arguments, config, sources, *job.mutable_recipe());
    if (kind == "capture_debug_bundle") return debug_recipe(arguments, config, sources, *job.mutable_recipe());
    if (kind == "ab_compare") return ab_recipe(arguments, config, sources, *job.mutable_recipe());
    throw std::invalid_argument("unsupported recipe");
}

void actions(const Json& arguments, std::span<const proto::PreparedSourceRef> sources, proto::SubmitJob& job) {
    require_sources(sources, 1);
    auto* sequence = job.mutable_actions();
    for (const auto& input : arguments.at("actions")) {
        auto* action = sequence->add_actions();
        const auto type = input.at("type").get<std::string>();
        if (type == "reset_temporal_state") action->mutable_reset_temporal_state();
        if (type == "wait_frames") {
            action->mutable_wait_frames()->set_frame_count(input.at("frames").get<std::uint32_t>());
        }
        if (type == "capture_screenshot") {
            auto* value = action->mutable_capture_screenshot();
            value->set_format(format(input.value("format", std::string("png"))));
            value->set_artifact_name(input.value("artifact_name", std::string{}));
        }
        if (type == "dump_texture") {
            auto* value = action->mutable_dump_texture();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_format(format(input.at("format").get<std::string>()));
            value->set_artifact_name(input.at("artifact_name").get<std::string>());
        }
        if (type == "dump_buffer") {
            auto* value = action->mutable_dump_buffer();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_format(format(input.at("format").get<std::string>()));
            value->set_artifact_name(input.at("artifact_name").get<std::string>());
        }
    }
}

void require_absolute(std::string_view value, std::string_view field, bool optional = false) {
    if ((!optional && value.empty()) || (!value.empty() && !std::filesystem::path(value).is_absolute())) {
        throw std::runtime_error(std::string(field) + " is not absolute");
    }
}

Json resource(const proto::ResourceDescriptor& value) {
    return {{"logical_name", value.logical_name()},
            {"kind", short_name(proto::ResourceKind_Name(value.kind()), "RESOURCE_KIND_")},
            {"width", value.width()}, {"height", value.height()}, {"depth", value.depth()},
            {"mip_level", value.mip_level()}, {"layer", value.layer()},
            {"internal_format", value.internal_format()}};
}

Json artifact(const proto::ArtifactMetadata& value) {
    require_absolute(value.path(), "artifact path");
    return {{"artifact_id", value.artifact_id()}, {"file_name", value.file_name()},
            {"kind", short_name(proto::ArtifactKind_Name(value.kind()), "ARTIFACT_KIND_")},
            {"format", short_name(proto::ArtifactFormat_Name(value.format()), "ARTIFACT_FORMAT_")},
            {"media_type", value.media_type()}, {"byte_size", value.byte_size()},
            {"resource", resource(value.resource())}, {"path", value.path()}};
}

Json artifacts(const google::protobuf::RepeatedPtrField<proto::ArtifactMetadata>& values) {
    Json result = Json::array();
    for (const auto& value : values) result.push_back(artifact(value));
    return result;
}

Json diagnostics(const google::protobuf::RepeatedPtrField<proto::ShaderDiagnostic>& values) {
    Json result = Json::array();
    for (const auto& value : values) {
        require_absolute(value.log_path(), "diagnostic log path", true);
        result.push_back({{"severity", short_name(proto::DiagnosticSeverity_Name(value.severity()),
                                                   "DIAGNOSTIC_SEVERITY_")},
                          {"file_name", value.file_name()}, {"line", value.line()}, {"column", value.column()},
                          {"message", value.message()}, {"log_path", value.log_path()}});
    }
    return result;
}

Json comparison(const proto::JobResult& value) {
    if (!value.has_comparison()) return nullptr;
    const auto& comparison = value.comparison();
    return {{"baseline_label", comparison.baseline_label()},
            {"candidate_label", comparison.candidate_label()},
            {"mean_absolute_error", comparison.mean_absolute_error()},
            {"root_mean_square_error", comparison.root_mean_square_error()},
            {"max_absolute_error", comparison.max_absolute_error()}};
}

ToolOutcome completed(const proto::JobCompleted& completed) {
    const auto& value = completed.result();
    if (value.manifest_path().empty()) {
        if (!value.artifacts().empty()) throw std::runtime_error("completed job omitted its artifact manifest");
    } else {
        require_absolute(value.manifest_path(), "manifest path");
    }
    const auto& timing = value.timings();
    return Json{{"success", true},
                {"kind", short_name(proto::JobResultKind_Name(value.kind()), "JOB_RESULT_KIND_")},
                {"diagnostics", diagnostics(value.shader_diagnostics())},
                {"comparison", comparison(value)},
                {"timings", {{"started_at_unix_ms", timing.started_at_unix_ms()},
                             {"completed_at_unix_ms", timing.completed_at_unix_ms()},
                             {"queue_ms", timing.queue_ms()}, {"execution_ms", timing.execution_ms()},
                             {"total_ms", timing.total_ms()}}},
                {"frame_ids", value.frame_ids()}, {"artifacts", artifacts(value.artifacts())},
                {"manifest_path", value.manifest_path()}};
}

ToolOutcome failed(const proto::JobFailed& failed) {
    const auto& error = failed.error();
    require_absolute(error.log_path(), "failure log path", true);
    Json details = Json::object();
    for (const auto& [key, value] : error.details()) details[key] = value;
    details["field"] = error.field();
    details["log_path"] = error.log_path();
    details["artifacts"] = artifacts(failed.artifacts());
    return ToolFailure{proto::ErrorCode_Name(error.code()), error.message(), error.retryable(), std::move(details)};
}

}

proto::ClientMessage JobProtocol::request(std::string_view tool_name, const Json& arguments,
    const SessionConfig& config, const proto::SceneContext& context,
    std::span<const proto::PreparedSourceRef> sources, std::string request_id) {
    if (request_id.empty() || config.workspace_id.empty()) throw std::invalid_argument("job identity is missing");
    proto::ClientMessage message;
    message.set_message_id("job-" + request_id);
    message.set_request_id(request_id);
    message.set_workspace_id(config.workspace_id);
    auto* job = message.mutable_submit_job();
    job->set_request_id(std::move(request_id));
    scene(config, context, *job);
    shader_config(arguments, *job);
    for (const auto& source : sources) job->add_sources()->CopyFrom(source);
    if (tool_name == "vibris_run_recipe") recipe(arguments, config, sources, *job);
    else if (tool_name == "vibris_run_actions") actions(arguments, sources, *job);
    else throw std::invalid_argument("unsupported job tool");
    return message;
}

bool JobProtocol::is_terminal(const proto::ServerMessage& message) noexcept {
    return message.has_job_completed() || message.has_job_failed();
}

ToolOutcome JobProtocol::terminal(const proto::ServerMessage& message) {
    if (message.has_job_completed()) return completed(message.job_completed());
    if (message.has_job_failed()) return failed(message.job_failed());
    throw std::invalid_argument("server message is not terminal");
}

}
