#include "job_protocol.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v1;
constexpr std::uint64_t queue_timeout_ms = 60'000;
constexpr std::uint64_t execution_timeout_ms = 120'000;
constexpr std::uint64_t total_timeout_ms = 180'000;

proto::ArtifactFormat format(std::string_view value) {
    if (value == "png") return proto::ARTIFACT_FORMAT_PNG;
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

void scale_timeouts(proto::SubmitJob& job) {
    std::uint64_t rendered_frames = 0;
    for (const auto& action : job.actions().actions()) {
        if (action.has_wait_frames()) rendered_frames += action.wait_frames().frame_count();
        else if (action.has_get_gpu_metrics()) rendered_frames += action.get_gpu_metrics().frames();
        else if (action.has_take_screenshot()) rendered_frames += action.take_screenshot().after_frames();
    }
    constexpr std::uint64_t setup_ms = 60'000;
    constexpr std::uint64_t ms_per_frame = 1'000;
    const auto measured_ms = rendered_frames > (std::numeric_limits<std::uint64_t>::max() - setup_ms) / ms_per_frame
        ? std::numeric_limits<std::uint64_t>::max()
        : setup_ms + rendered_frames * ms_per_frame;
    const auto execution = std::max(execution_timeout_ms, measured_ms);
    job.mutable_timeouts()->set_execution_timeout_ms(execution);
    job.mutable_timeouts()->set_total_timeout_ms(
        execution > std::numeric_limits<std::uint64_t>::max() - queue_timeout_ms
            ? std::numeric_limits<std::uint64_t>::max()
            : execution + queue_timeout_ms);
}

void config_values(const Json& values, proto::ShaderConfig& config) {
    for (const auto& [key, value] : values.items()) {
        (*config.mutable_values())[key] = config_value(value);
    }
}

void recipe_config(const Json& arguments, proto::SubmitJob& job) {
    auto* named = job.add_shader_configs();
    named->set_id(arguments.value("__vibris_config_id", std::string("config")));
    if (arguments.contains("config")) config_values(arguments.at("config"), *named->mutable_config());
    else named->set_preserve(true);
}

void named_configs(const Json& arguments, proto::SubmitJob& job) {
    if (!arguments.contains("configs")) return;
    for (const auto& input : arguments.at("configs")) {
        auto* named = job.add_shader_configs();
        named->set_id(input.at("id").get<std::string>());
        if (input.contains("values")) config_values(input.at("values"), *named->mutable_config());
        else named->set_preserve(true);
    }
}

proto::Action* add_action(proto::ActionSequence& sequence) {
    return sequence.add_actions();
}

void load(proto::ActionSequence& sequence, const proto::PreparedSourceRef& source,
    std::string source_id, std::string config_id, std::string case_id, bool continue_on_failure = false) {
    auto* action = add_action(sequence)->mutable_load_shader();
    action->set_source_uuid(source.uuid());
    action->set_source_id(std::move(source_id));
    action->set_config_id(std::move(config_id));
    action->set_case_id(std::move(case_id));
    action->set_continue_on_failure(continue_on_failure);
}

void wait(proto::ActionSequence& sequence, std::uint32_t frames) {
    if (frames != 0) add_action(sequence)->mutable_wait_frames()->set_frame_count(frames);
}

void load_and_screenshot_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 1);
    load(sequence, sources.front(), "source", "config", "source--config");
    auto* capture = add_action(sequence)->mutable_take_screenshot();
    capture->set_artifact_name("screenshot");
    capture->set_format(format(arguments.value("screenshot_format", std::string("png"))));
    capture->set_after_frames(arguments.value("warmup_frames", config.default_warmup_frames));
}

void debug_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 1);
    load(sequence, sources.front(), "source", "config", "source--config");
    wait(sequence, arguments.value("warmup_frames", config.default_warmup_frames));
    if (arguments.value("screenshot", false)) {
        auto* capture = add_action(sequence)->mutable_take_screenshot();
        capture->set_artifact_name("screenshot");
        capture->set_format(proto::ARTIFACT_FORMAT_PNG);
    }
    for (const auto& texture : arguments.value("textures", Json::array())) {
        const auto name = texture.get<std::string>();
        auto* capture = add_action(sequence)->mutable_dump_texture_v2();
        capture->set_logical_name(name);
        capture->set_artifact_name(name);
        capture->set_format(proto::ARTIFACT_FORMAT_BIN);
    }
    for (const auto& buffer : arguments.value("buffers", Json::array())) {
        const auto name = buffer.get<std::string>();
        auto* capture = add_action(sequence)->mutable_dump_buffer();
        capture->set_logical_name(name);
        capture->set_artifact_name(name);
    }
}

void add_ab_capture(proto::ActionSequence& sequence, const Json& capture, std::string artifact_name) {
    const auto type = capture.at("type").get<std::string>();
    if (type == "screenshot") {
        auto* value = add_action(sequence)->mutable_take_screenshot();
        value->set_artifact_name(std::move(artifact_name));
        value->set_format(format(capture.value("format", std::string("png"))));
        return;
    }
    const auto default_format = type == "buffer" ? std::string("bin") : std::string("png");
    if (type == "texture") {
        auto* value = add_action(sequence)->mutable_dump_texture_v2();
        value->set_logical_name(capture.at("name").get<std::string>());
        value->set_artifact_name(std::move(artifact_name));
        value->set_format(format(capture.value("format", default_format)));
        return;
    }
    auto* value = add_action(sequence)->mutable_dump_buffer();
    value->set_logical_name(capture.at("name").get<std::string>());
    value->set_artifact_name(std::move(artifact_name));
}

void ab_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 2);
    const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
    load(sequence, sources[0], "a", "config", "a--config");
    wait(sequence, warmup);
    std::size_t index = 0;
    for (const auto& capture : arguments.at("captures")) {
        add_ab_capture(sequence, capture, "a-" + std::to_string(index++));
    }
    load(sequence, sources[1], "b", "config", "b--config");
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

void profile_recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::ActionSequence& sequence) {
    require_sources(sources, 1);
    load(
        sequence,
        sources.front(),
        arguments.value("__vibris_source_id", std::string("source")),
        arguments.value("__vibris_config_id", std::string("config")),
        arguments.value("__vibris_case_id", std::string("source--config")));
    wait(sequence, arguments.value("warmup_frames", config.default_warmup_frames));
    add_action(sequence)->mutable_get_gpu_metrics()->set_frames(arguments.at("frames").get<std::uint32_t>());
}

void profile_artifacts(const Json& arguments, std::string kind, proto::SubmitJob& job) {
    auto* options = job.mutable_result_artifacts();
    options->set_json(true);
    options->set_csv(arguments.value("result_csv", false));
    options->set_kind(arguments.value("__vibris_result_kind", kind));
    options->set_attempt(arguments.value("__vibris_attempt", std::uint32_t{1}));
    for (const auto& unit : arguments.value("converted_units", Json::array())) {
        options->add_converted_units(unit.get<std::string>());
    }
    for (const auto& diagnostic : arguments.value("__vibris_previous_attempts", Json::array())) {
        auto* output = options->add_previous_attempts();
        output->set_attempt(diagnostic.at("attempt").get<std::uint32_t>());
        output->set_status(diagnostic.at("status").get<std::string>());
        output->set_retryable(diagnostic.value("retryable", false));
        const auto error = diagnostic.find("error");
        if (error != diagnostic.end() && error->is_object()) {
            output->set_error_code(error->value("error_code", std::string{}));
            output->set_message(error->value("message", std::string{}));
        }
    }
}

void matrix(const Json& arguments, std::span<const proto::PreparedSourceRef> prepared,
    const Json& template_actions, proto::SubmitJob& job);

void recipe(const Json& arguments, const SessionConfig& config,
    std::span<const proto::PreparedSourceRef> sources, proto::SubmitJob& job) {
    const auto kind = arguments.at("recipe").get<std::string>();
    if (kind != "profile_matrix") recipe_config(arguments, job);
    if (kind == "profile") {
        profile_artifacts(arguments, kind, job);
        return profile_recipe(arguments, config, sources, *job.mutable_actions());
    }
    if (kind == "profile_matrix") {
        profile_artifacts(arguments, kind, job);
        Json template_actions = Json::array();
        const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
        if (warmup != 0) template_actions.push_back({{"type", "wait_frames"}, {"frames", warmup}});
        template_actions.push_back({{"type", "get_gpu_metrics"}, {"frames", arguments.at("frames")}});
        return matrix(arguments, sources, template_actions, job);
    }
    if (kind == "load_and_screenshot") {
        return load_and_screenshot_recipe(arguments, config, sources, *job.mutable_actions());
    }
    if (kind == "capture_debug_bundle") return debug_recipe(arguments, config, sources, *job.mutable_actions());
    if (kind == "ab_compare") return ab_recipe(arguments, config, sources, *job.mutable_actions());
    throw std::invalid_argument("unsupported recipe");
}

using SourceMap = std::unordered_map<std::string, const proto::PreparedSourceRef*>;

SourceMap source_map(const Json& arguments, std::span<const proto::PreparedSourceRef> sources) {
    if (!arguments.contains("sources")) {
        if (!sources.empty()) throw std::invalid_argument("prepared sources have no named declarations");
        return {};
    }
    if (arguments.at("sources").size() != sources.size()) {
        throw std::invalid_argument("prepared source count does not match named declarations");
    }
    SourceMap result;
    for (std::size_t index = 0; index < sources.size(); ++index) {
        result.emplace(arguments.at("sources")[index].at("id").get<std::string>(), &sources[index]);
    }
    return result;
}

void append_actions(const Json& inputs, const SourceMap& sources, proto::ActionSequence& sequence,
    std::string_view artifact_prefix = {}) {
    for (const auto& input : inputs) {
        auto* action = sequence.add_actions();
        const auto type = input.at("type").get<std::string>();
        if (type == "reset_temporal_state") {
            action->mutable_reset_temporal_state();
        } else if (type == "wait_frames") {
            action->mutable_wait_frames()->set_frame_count(input.at("frames").get<std::uint32_t>());
        } else if (type == "take_screenshot") {
            auto* value = action->mutable_take_screenshot();
            value->set_format(format(input.value("format", std::string("png"))));
            value->set_artifact_name(std::string(artifact_prefix) +
                input.value("artifact_name", std::string("screenshot")));
            value->set_after_frames(input.value("after_frames", std::uint32_t{0}));
        } else if (type == "dump_texture") {
            auto* value = action->mutable_dump_texture_v2();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_format(format(input.at("format").get<std::string>()));
            value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
        } else if (type == "dump_buffer") {
            auto* value = action->mutable_dump_buffer();
            value->set_logical_name(input.at("name").get<std::string>());
            value->set_artifact_name(std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
        } else if (type == "get_capture_status") action->mutable_get_capture_status();
        else if (type == "load_shader") {
            const auto source_id = input.at("source").get<std::string>();
            const auto source = sources.find(source_id);
            if (source == sources.end()) throw std::invalid_argument("load action references an unknown source");
            auto* load_action = action->mutable_load_shader();
            load_action->set_source_uuid(source->second->uuid());
            load_action->set_source_id(source_id);
            load_action->set_config_id(input.at("config").get<std::string>());
            load_action->set_case_id(source_id + "--" + load_action->config_id());
            load_action->set_continue_on_failure(true);
        } else if (type == "capture_pass") {
            auto* value = action->mutable_capture_pass();
            value->set_pass(input.at("pass").get<std::string>());
            if (input.contains("path")) value->set_path(input.at("path").get<std::string>());
        } else if (type == "capture_multi") {
            auto* value = action->mutable_capture_multi();
            value->set_type(input.at("capture_type").get<std::string>());
            if (input.contains("path")) value->set_path(input.at("path").get<std::string>());
        } else if (type == "inspect_shader") action->mutable_inspect_shader();
        else if (type == "get_gpu_metrics") {
            action->mutable_get_gpu_metrics()->set_frames(input.at("frames").get<std::uint32_t>());
        } else if (type == "list_buffers") action->mutable_list_buffers();
        else if (type == "list_textures") action->mutable_list_textures_v2();
        else if (type == "get_patched_shaders") {
            action->mutable_get_patched_shaders()->set_artifact_name(
                std::string(artifact_prefix) + input.at("artifact_name").get<std::string>());
        }
        else throw std::invalid_argument("unsupported action type");
    }
}

void actions(const Json& arguments, std::span<const proto::PreparedSourceRef> prepared, proto::SubmitJob& job) {
    named_configs(arguments, job);
    const auto sources = source_map(arguments, prepared);
    append_actions(arguments.at("actions"), sources, *job.mutable_actions());
}

void matrix(const Json& arguments, std::span<const proto::PreparedSourceRef> prepared,
    const Json& template_actions, proto::SubmitJob& job) {
    named_configs(arguments, job);
    const auto sources = source_map(arguments, prepared);
    auto& sequence = *job.mutable_actions();
    for (const auto& source_value : arguments.at("matrix").at("sources")) {
        const auto source_id = source_value.get<std::string>();
        const auto source = sources.find(source_id);
        if (source == sources.end()) throw std::invalid_argument("matrix references an unknown source");
        for (const auto& config_value : arguments.at("matrix").at("configs")) {
            const auto config_id = config_value.get<std::string>();
            const auto case_id = source_id + "--" + config_id;
            load(sequence, *source->second, source_id, config_id, case_id, true);
            append_actions(template_actions, sources, sequence, case_id + "--");
        }
    }
}

void matrix(const Json& arguments, std::span<const proto::PreparedSourceRef> prepared, proto::SubmitJob& job) {
    matrix(arguments, prepared, arguments.at("actions"), job);
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
            {"mip_level", value.mip_level()}, {"mip_levels", value.mip_levels()}, {"layer", value.layer()},
            {"internal_format", value.internal_format()}, {"category", value.category()},
            {"target", value.texture_target()}, {"channel_layout", value.channel_layout()},
            {"numeric_class", value.numeric_class()}, {"component_bits", value.component_bits()},
            {"readback_format", value.readback_format()}, {"readback_type", value.readback_type()}};
}

Json artifact(const proto::ArtifactMetadata& value) {
    require_absolute(value.path(), "artifact path");
    return {{"artifact_id", value.artifact_id()}, {"file_name", value.file_name()},
            {"kind", short_name(proto::ArtifactKind_Name(value.kind()), "ARTIFACT_KIND_")},
            {"format", short_name(proto::ArtifactFormat_Name(value.format()), "ARTIFACT_FORMAT_")},
            {"media_type", value.media_type()}, {"byte_size", value.byte_size()},
            {"resource", resource(value.resource())},
            {"role", short_name(proto::ArtifactRole_Name(value.role()), "ARTIFACT_ROLE_")},
            {"subresource_index", value.has_subresource_index() ? Json(value.subresource_index()) : Json(nullptr)},
            {"path", value.path()}};
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

Json artifact_groups(const google::protobuf::RepeatedPtrField<proto::ArtifactGroup>& values) {
    Json result = Json::array();
    for (const auto& value : values) {
        result.push_back({{"name", value.name()}, {"resource", resource(value.resource())},
                          {"artifacts", artifacts(value.artifacts())}});
    }
    return result;
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
                {"artifact_groups", artifact_groups(value.artifact_groups())},
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
    if (const auto preset = arguments.find("__vibris_preset"); preset != arguments.end()) {
        auto* provenance = job->mutable_benchmark_provenance();
        provenance->set_preset_id(preset->at("preset_id").get<std::string>());
        provenance->set_preset_version(preset->at("version").get<std::string>());
        provenance->set_preset_display_name(preset->at("display_name").get<std::string>());
    }
    for (const auto& source : sources) job->add_sources()->CopyFrom(source);
    if (tool_name == "vibris_run_recipe") recipe(arguments, config, sources, *job);
    else if (tool_name == "vibris_run_actions") actions(arguments, sources, *job);
    else if (tool_name == "vibris_run_matrix") matrix(arguments, sources, *job);
    else throw std::invalid_argument("unsupported job tool");
    scale_timeouts(*job);
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