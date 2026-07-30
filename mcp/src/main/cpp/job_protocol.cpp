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
    auto* config = job.mutable_shader_config();
    for (const auto& [key, value] : arguments.at("config").items()) {
        (*config->mutable_values())[key] = config_value(value);
    }
}

void shader_config(const Json& arguments, proto::ShaderConfig& config) {
    if (!arguments.contains("config")) return;
    for (const auto& [key, value] : arguments.at("config").items()) {
        (*config.mutable_values())[key] = config_value(value);
    }
}

proto::Action* add_action(proto::ActionSequence& sequence) {
    return sequence.add_actions();
}

void activate(proto::ActionSequence& sequence, const proto::PreparedSourceRef& source) {
    add_action(sequence)->mutable_activate_source()->set_source_uuid(source.uuid());
}

void reset(proto::ActionSequence& sequence) {
    add_action(sequence)->mutable_reset_temporal_state();
}

void wait(proto::ActionSequence& sequence, std::uint32_t frames) {
    if (frames != 0) add_action(sequence)->mutable_wait_frames()->set_frame_count(frames);
}

void reload_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 1);
    activate(sequence, sources.front());
    reset(sequence);
    wait(sequence, arguments.value("warmup_frames", config.default_warmup_frames));
    auto* capture = add_action(sequence)->mutable_capture_screenshot();
    capture->set_artifact_name("screenshot");
    capture->set_format(format(arguments.value("screenshot_format", std::string("png"))));
}

void debug_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 1);
    activate(sequence, sources.front());
    reset(sequence);
    wait(sequence, arguments.value("warmup_frames", config.default_warmup_frames));
    if (arguments.value("screenshot", false)) {
        auto* capture = add_action(sequence)->mutable_capture_screenshot();
        capture->set_artifact_name("screenshot");
        capture->set_format(proto::ARTIFACT_FORMAT_PNG);
    }
    for (const auto& texture : arguments.value("textures", Json::array())) {
        const auto name = texture.get<std::string>();
        auto* capture = add_action(sequence)->mutable_capture_texture();
        capture->set_logical_name(name);
        capture->set_artifact_name(name);
        capture->set_format(proto::ARTIFACT_FORMAT_RAW);
    }
    for (const auto& buffer : arguments.value("buffers", Json::array())) {
        const auto name = buffer.get<std::string>();
        auto* capture = add_action(sequence)->mutable_capture_buffer();
        capture->set_logical_name(name);
        capture->set_artifact_name(name);
        capture->set_format(proto::ARTIFACT_FORMAT_BIN);
    }
}

void add_ab_capture(proto::ActionSequence& sequence, const Json& capture, std::string artifact_name) {
    const auto type = capture.at("type").get<std::string>();
    if (type == "screenshot") {
        auto* value = add_action(sequence)->mutable_capture_screenshot();
        value->set_artifact_name(std::move(artifact_name));
        value->set_format(format(capture.value("format", std::string("png"))));
        return;
    }
    const auto default_format = type == "buffer" ? std::string("bin") : std::string("png");
    if (type == "texture") {
        auto* value = add_action(sequence)->mutable_capture_texture();
        value->set_logical_name(capture.at("name").get<std::string>());
        value->set_artifact_name(std::move(artifact_name));
        value->set_format(format(capture.value("format", default_format)));
        return;
    }
    auto* value = add_action(sequence)->mutable_capture_buffer();
    value->set_logical_name(capture.at("name").get<std::string>());
    value->set_artifact_name(std::move(artifact_name));
    value->set_format(format(capture.value("format", default_format)));
}

void ab_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 2);
    const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
    activate(sequence, sources[0]);
    reset(sequence);
    wait(sequence, warmup);
    std::size_t index = 0;
    for (const auto& capture : arguments.at("captures")) {
        add_ab_capture(sequence, capture, "a-" + std::to_string(index++));
    }
    activate(sequence, sources[1]);
    reset(sequence);
    wait(sequence, warmup);
    index = 0;
    for (const auto& capture : arguments.at("captures")) {
        add_ab_capture(sequence, capture, "b-" + std::to_string(index++));
    }
    auto* compare = add_action(sequence)->mutable_compare_captures();
    compare->set_baseline_capture_index(0);
    compare->set_candidate_capture_index(1);
    compare->set_baseline_label(arguments.at("a").at("label").get<std::string>());
    compare->set_candidate_label(arguments.at("b").at("label").get<std::string>());
}

void recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::SubmitJob& job) {
    const auto kind = arguments.at("recipe").get<std::string>();
    if (kind == "reload_and_capture") return reload_recipe(arguments, config, sources, *job.mutable_actions());
    if (kind == "capture_debug_bundle") return debug_recipe(arguments, config, sources, *job.mutable_actions());
    if (kind == "ab_compare") return ab_recipe(arguments, config, sources, *job.mutable_actions());
    throw std::invalid_argument("unsupported recipe");
}

void actions(const Json& arguments, std::span<const proto::PreparedSourceRef> sources, proto::SubmitJob& job) {
    if (sources.size() > 1) throw std::invalid_argument("custom actions accept at most one prepared source");
    auto* sequence = job.mutable_actions();
    if (!sources.empty()) activate(*sequence, sources.front());
    for (const auto& input : arguments.at("actions")) {
        auto* action = sequence->add_actions();
        const auto type = input.at("type").get<std::string>();
        if (type == "reset_temporal_state") {
            action->mutable_reset_temporal_state();
        } else if (type == "wait_frames") {
            action->mutable_wait_frames()->set_frame_count(input.at("frames").get<std::uint32_t>());
        } else if (type == "capture_screenshot") {
            auto* value = action->mutable_capture_screenshot();
            value->set_format(format(input.value("format", std::string("png"))));
            value->set_artifact_name(input.value("artifact_name", std::string{}));
        } else if (type == "capture_texture") {
            auto* value = action->mutable_capture_texture();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_format(format(input.at("format").get<std::string>()));
            value->set_artifact_name(input.at("artifact_name").get<std::string>());
        } else if (type == "capture_buffer") {
            auto* value = action->mutable_capture_buffer();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_format(format(input.at("format").get<std::string>()));
            value->set_artifact_name(input.at("artifact_name").get<std::string>());
        } else if (type == "get_capture_status") action->mutable_get_capture_status();
        else if (type == "reload_shader") {
            auto* reload = action->mutable_reload_shader();
            if (input.contains("config")) shader_config(input, *reload->mutable_config());
        } else if (type == "capture_pass") {
            auto* value = action->mutable_capture_pass();
            value->set_pass(input.at("pass").get<std::string>());
            if (input.contains("path")) value->set_path(input.at("path").get<std::string>());
        } else if (type == "capture_multi") {
            auto* value = action->mutable_capture_multi();
            value->set_type(input.at("capture_type").get<std::string>());
            if (input.contains("path")) value->set_path(input.at("path").get<std::string>());
        } else if (type == "get_shader_status") action->mutable_get_shader_status();
        else if (type == "get_shader_errors") action->mutable_get_shader_errors();
        else if (type == "schedule_screenshot") {
            action->mutable_schedule_screenshot()->set_frames(input.value("frames", std::uint32_t{1}));
        } else if (type == "get_screenshot_result") action->mutable_get_screenshot_result();
        else if (type == "get_gpu_metrics") {
            action->mutable_get_gpu_metrics()->set_frames(input.at("frames").get<std::uint32_t>());
        } else if (type == "list_ssbos") action->mutable_list_ssbos();
        else if (type == "dump_ssbo") {
            action->mutable_dump_ssbo()->set_index(input.at("index").get<std::uint32_t>());
        } else if (type == "list_textures") action->mutable_list_textures();
        else if (type == "dump_texture") {
            auto* value = action->mutable_dump_texture();
            if (input.contains("name")) value->set_name(input.at("name").get<std::string>());
            else value->set_id(input.at("id").get<std::uint32_t>());
            value->set_raw(input.value("raw", false));
        } else if (type == "list_patched_shaders") action->mutable_list_patched_shaders();
        else throw std::invalid_argument("unsupported action type");
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

Json action_results(const proto::JobResult& value) {
    Json results = Json::array();
    for (const auto& action : value.action_results()) {
        Json payload = Json::object();
        if (!action.json().empty()) payload = Json::parse(action.json());
        results.push_back({{"action_index", action.action_index()},
                           {"kind", short_name(proto::JobActionKind_Name(action.kind()), "JOB_ACTION_KIND_")},
                           {"result", std::move(payload)}});
    }
    return results;
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
                {"action_results", action_results(value)},
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
