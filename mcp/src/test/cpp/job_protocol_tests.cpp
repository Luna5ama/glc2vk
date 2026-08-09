#include "job_protocol.hpp"
#include "pending_request_registry.hpp"
#include "grpc_reconnect_fixture.hpp"
#include "scene_context_resolver.hpp"
#include "state_error.hpp"
#include "synchronous_job_fixture.hpp"
#include "synchronous_job_runner.hpp"
#include "workspace_source_fixture.hpp"
#include "workspace_artifact_link.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <filesystem>
#include <future>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace {

namespace proto = ::vibris::control::v1;
using vibris::mcp::JobProtocol;
using vibris::mcp::Json;
using vibris::mcp::PendingRequestRegistry;
using vibris::mcp::SceneContextResolver;
using vibris::mcp::SessionConfig;
using vibris::mcp::StateError;
using vibris::mcp::SynchronousJobRunner;
using vibris::mcp::ToolFailure;
using vibris::mcp::ToolOutcome;
using vibris::mcp::test::MetricsJobService;
using vibris::mcp::test::MetricsJobServer;
using vibris::mcp::test::TerminalJobServer;
using vibris::mcp::test::TempDirectory;
using vibris::mcp::test::WorkspaceFixture;
using vibris::mcp::test::ReconnectServer;
using namespace std::chrono_literals;

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

SessionConfig config() {
    return {.workspace_id = "workspace-id",
            .save_id = "shader-test-world",
            .dimension_id = "minecraft:the_nether",
            .time_preset_id = "sunset",
            .camera_preset_id = "village-rooftop",
            .fov = 72.5,
            .default_warmup_frames = 19};
}

proto::PreparedSourceRef source(std::string uuid) {
    proto::PreparedSourceRef result;
    result.set_uuid(std::move(uuid));
    result.mutable_origin()->mutable_workspace()->set_display_name("workspace");
    result.set_file_count(3);
    result.set_total_bytes(41);
    return result;
}

proto::ListPresetsResponse presets() {
    proto::ListPresetsResponse result;
    auto* preset = result.add_presets();
    preset->set_preset_id("village-rooftop");
    preset->set_display_name("Village rooftop");
    preset->set_version("2");
    auto* context = preset->mutable_context();
    context->set_save_id("shader-test-world");
    context->set_dimension_id("minecraft:the_nether");
    context->set_time_preset_id("sunset");
    context->set_weather_preset_id("clear");
    context->set_camera_preset_id("village-rooftop");
    context->set_fov(60.0);
    context->mutable_resolution()->set_width(1920);
    context->mutable_resolution()->set_height(1080);
    context->set_settings_preset_id("quality");
    return result;
}

class DeadlineJobService final : public proto::VibrisControl::Service {
public:
    explicit DeadlineJobService(std::filesystem::path pending) : pending_(std::move(pending)) {}

private:
    grpc::Status Control(grpc::ServerContext*,
        grpc::ServerReaderWriter<proto::ServerMessage, proto::ClientMessage>* stream) override {
        proto::ClientMessage request;
        std::filesystem::path source;
        while (stream->Read(&request)) {
            if (request.has_client_hello()) {
                proto::ServerMessage hello;
                hello.mutable_protocol_version()->set_major(1);
                hello.set_workspace_id(request.workspace_id());
                auto* server = hello.mutable_server_hello();
                server->set_ready(true);
                server->set_pending_shaders_root(std::filesystem::absolute(pending_).string());
                server->mutable_limits()->set_max_source_bytes(1024 * 1024);
                server->mutable_limits()->set_max_source_files(128);
                if (!stream->Write(hello)) break;
            } else if (request.has_submit_job()) {
                source = pending_ / request.submit_job().sources(0).uuid();
                proto::ServerMessage accepted;
                accepted.set_request_id(request.request_id());
                accepted.mutable_job_accepted()->set_request_id(request.request_id());
                if (!stream->Write(accepted)) break;
            } else if (request.has_cancel_job()) {
                break;
            }
        }
        if (!source.empty()) std::filesystem::remove_all(source);
        return grpc::Status::OK;
    }

    std::filesystem::path pending_;
};

class DeadlineJobServer final {
public:
    explicit DeadlineJobServer(const std::filesystem::path& pending) : service_(pending) {
        grpc::ServerBuilder builder;
        builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port_);
        builder.RegisterService(&service_);
        server_ = builder.BuildAndStart();
        if (!server_ || port_ == 0) throw std::runtime_error("failed to bind deadline fixture server");
    }

    ~DeadlineJobServer() { shutdown(); }
    [[nodiscard]] int port() const noexcept { return port_; }

    void shutdown() {
        if (!server_) return;
        server_->Shutdown();
        server_->Wait();
        server_.reset();
    }

private:
    int port_ = 0;
    DeadlineJobService service_;
    std::unique_ptr<grpc::Server> server_;
};

void request_mapping() {
    const std::vector sources{source("11111111-1111-4111-8111-111111111111")};
    const Json arguments{
        {"recipe", "load_and_screenshot"},
        {"screenshot_format", "png"},
        {"config", {{"SETTING_SAMPLE_COUNT", 32}, {"SETTING_CLOUDS", false}}},
        {"__vibris_preset", {{"preset_id", "village-rooftop"}, {"version", "2"},
                             {"display_name", "Village rooftop"}}},
    };
    const auto context = SceneContextResolver::resolve(config(), presets());

    const auto message = JobProtocol::request(
        "vibris_run_recipe", arguments, config(), context, sources, "request-1");

    require(message.message_id() == "job-request-1" && message.request_id() == "request-1",
        "SubmitJob envelope IDs were not exact.");
    require(message.workspace_id() == "workspace-id" && message.submit_job().workspace_id() == "workspace-id",
        "SubmitJob workspace ownership was not copied from scene config.");
    const auto& job = message.submit_job();
    require(job.request_id() == "request-1" && job.sources_size() == 1 &&
            job.sources(0).uuid() == sources.front().uuid(),
        "PreparedSourceRef was not copied into SubmitJob.");
    require(job.context().save_id() == "shader-test-world" &&
            job.context().dimension_id() == "minecraft:the_nether" &&
            job.context().time_preset_id() == "sunset" &&
            job.context().weather_preset_id() == "clear" &&
            job.context().camera_preset_id() == "village-rooftop" &&
            job.context().settings_preset_id() == "quality" &&
            job.context().resolution().width() == 1920 && job.context().resolution().height() == 1080 &&
            job.context().fov() == 72.5,
        "Full matched preset context was not copied with the configured FOV override.");
    require(job.has_actions() && job.actions().actions_size() == 2 &&
            job.actions().actions(0).has_load_shader() &&
            job.actions().actions(0).load_shader().source_uuid() == sources.front().uuid() &&
            job.actions().actions(1).take_screenshot().after_frames() == 19 &&
            job.actions().actions(1).take_screenshot().format() == proto::ARTIFACT_FORMAT_PNG,
        "load_and_screenshot was not expanded into runtime actions.");
    require(job.timeouts().queue_timeout_ms() == 60'000 && job.timeouts().execution_timeout_ms() == 120'000 &&
            job.timeouts().total_timeout_ms() == 180'000,
        "SubmitJob timeout defaults were not mapped exactly.");
    require(job.shader_configs_size() == 1 && job.shader_configs(0).id() == "config" &&
                job.shader_configs(0).config().values().at("SETTING_SAMPLE_COUNT") == "32" &&
                job.shader_configs(0).config().values().at("SETTING_CLOUDS") == "false",
        "Shader config was not copied into SubmitJob.");
    require(job.benchmark_provenance().preset_id() == "village-rooftop" &&
            job.benchmark_provenance().preset_version() == "2" &&
            job.benchmark_provenance().preset_display_name() == "Village rooftop",
        "Preset version provenance was not copied into SubmitJob.");
}

void remaining_execution_mappings() {
    const auto context = SceneContextResolver::resolve(config(), presets());
    const std::vector one_source{source("33333333-3333-4333-8333-333333333333")};
    const auto debug = JobProtocol::request("vibris_run_recipe",
        {{"recipe", "capture_debug_bundle"}, {"warmup_frames", 5}, {"screenshot", true},
         {"textures", Json::array({"colortex5"})}, {"buffers", Json::array({"debugSsbo"})}},
        config(), context, one_source, "request-debug");
    const auto& debug_actions = debug.submit_job().actions();
    require(debug_actions.actions_size() == 5 &&
            debug_actions.actions(0).load_shader().source_uuid() == one_source.front().uuid() &&
            debug_actions.actions(1).wait_frames().frame_count() == 5 &&
            debug_actions.actions(2).take_screenshot().artifact_name() == "screenshot" &&
            debug_actions.actions(3).dump_texture_v2().logical_name() == "colortex5" &&
            debug_actions.actions(3).dump_texture_v2().format() == proto::ARTIFACT_FORMAT_BIN &&
            debug_actions.actions(4).dump_buffer().logical_name() == "debugSsbo",
        "capture_debug_bundle was not expanded into runtime actions.");

    const std::vector two_sources{source("44444444-4444-4444-8444-444444444444"),
                                  source("55555555-5555-4555-8555-555555555555")};
    const auto ab = JobProtocol::request("vibris_run_recipe",
        {{"recipe", "ab_compare"},
         {"a", {{"label", "baseline"}, {"source", {{"kind", "workspace"}}}}},
         {"b", {{"label", "candidate"}, {"source", {{"kind", "workspace"}}}}},
         {"captures", Json::array({{{"type", "screenshot"}},
                                    {{"type", "texture"}, {"name", "colortex5"}},
                                    {{"type", "buffer"}, {"name", "debugSsbo"}}})}},
        config(), context, two_sources, "request-ab");
    const auto& ab_actions = ab.submit_job().actions();
    require(ab_actions.actions_size() == 11 &&
            ab_actions.actions(0).load_shader().source_uuid() == two_sources[0].uuid() &&
            ab_actions.actions(2).take_screenshot().format() == proto::ARTIFACT_FORMAT_PNG &&
            ab_actions.actions(3).dump_texture_v2().format() == proto::ARTIFACT_FORMAT_PNG &&
            ab_actions.actions(4).has_dump_buffer() &&
            ab_actions.actions(5).load_shader().source_uuid() == two_sources[1].uuid() &&
            ab_actions.actions(10).compare_captures().baseline_label() == "baseline" &&
            ab_actions.actions(10).compare_captures().candidate_label() == "candidate",
        "ab_compare was not expanded into runtime actions.");

    const Json action_arguments{
        {"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "quality"}, {"values", Json::object()}}})},
        {"actions", Json::array({
            {{"type", "load_shader"}, {"source", "candidate"}, {"config", "quality"}},
            {{"type", "wait_frames"}, {"frames", 3}},
            {{"type", "take_screenshot"}, {"artifact_name", "beauty"}},
            {{"type", "dump_texture"}, {"name", "colortex5.main"}, {"format", "bin"},
             {"artifact_name", "texture"}},
            {{"type", "dump_buffer"}, {"name", "iris_ssbo_6"},
             {"artifact_name", "buffer"}},
            {{"type", "get_patched_shaders"}, {"artifact_name", "patched"}},
        })}};
    const auto actions = JobProtocol::request(
        "vibris_run_actions", action_arguments, config(), context, one_source, "request-actions");
    const auto& sequence = actions.submit_job().actions();
    require(sequence.actions_size() == 6 && sequence.actions(0).has_load_shader() &&
            sequence.actions(0).load_shader().continue_on_failure() &&
            sequence.actions(1).wait_frames().frame_count() == 3 &&
            sequence.actions(2).take_screenshot().format() == proto::ARTIFACT_FORMAT_PNG &&
            sequence.actions(3).dump_texture_v2().logical_name() == "colortex5.main" &&
            sequence.actions(4).dump_buffer().artifact_name() == "buffer" &&
            sequence.actions(5).get_patched_shaders().artifact_name() == "patched",
        "Allowed action sequence was not mapped exactly.");
}

void incomplete_preset_rejected() {
    auto response = presets();
    response.mutable_presets(0)->mutable_context()->clear_weather_preset_id();
    try {
        (void)SceneContextResolver::resolve(config(), response);
        throw std::runtime_error("Incomplete preset reached SubmitJob mapping.");
    } catch (const StateError& error) {
        require(error.code() == "INVALID_PRESET", "Incomplete preset returned the wrong error code.");
    }
}

void default_settings_disambiguates_scene_presets() {
    auto response = presets();
    response.mutable_presets(0)->mutable_context()->set_settings_preset_id("cinematic");
    auto* fallback = response.add_presets();
    fallback->set_preset_id("village-rooftop-default");
    fallback->set_display_name("Village rooftop default");
    fallback->set_version("2");
    fallback->mutable_context()->CopyFrom(response.presets(0).context());
    fallback->mutable_context()->set_settings_preset_id("default");
    const auto resolved = SceneContextResolver::resolve(config(), response);
    require(resolved.settings_preset_id() == "default",
        "The implicit default settings preset did not disambiguate the frozen configure schema.");
}

void empty_actions_mapping() {
    const auto context = SceneContextResolver::resolve(config(), presets());
    const auto message = JobProtocol::request(
        "vibris_run_actions", {{"actions", Json::array()}}, config(), context, {}, "request-empty");
    require(message.submit_job().has_actions() && message.submit_job().actions().actions().empty(),
        "Empty public actions gained an implicit source load.");
}

void progress_does_not_consume_terminal() {
    PendingRequestRegistry registry(1);
    proto::ClientMessage request;
    request.set_request_id("request-progress");
    std::size_t callbacks = 0;
    std::size_t terminals = 0;
    require(registry.add(std::move(request), [&](const grpc::Status& status, const proto::ServerMessage& message) {
        require(status.ok(), "Progress registry callback unexpectedly failed.");
        ++callbacks;
        if (JobProtocol::is_terminal(message)) ++terminals;
    }), "Job request was not registered.");

    proto::ServerMessage accepted;
    accepted.set_request_id("request-progress");
    accepted.mutable_job_accepted()->set_request_id("request-progress");
    require(registry.resolve(accepted), "JobAccepted was not observed.");
    proto::ServerMessage progress;
    progress.set_request_id("request-progress");
    progress.mutable_job_progress()->set_request_id("request-progress");
    require(registry.resolve(progress) && registry.size() == 1,
        "JobProgress consumed the pending request before its terminal result.");
    proto::ServerMessage completed;
    completed.set_request_id("request-progress");
    completed.mutable_job_completed()->set_request_id("request-progress");
    require(registry.resolve(completed) && registry.size() == 0 && callbacks == 3 && terminals == 1,
        "The registry did not deliver exactly one terminal after progress.");
    require(!registry.resolve(completed) && callbacks == 3,
        "A duplicate terminal was delivered after the request retired.");
}

void resume_registration_replays_terminal_without_submit() {
    PendingRequestRegistry registry(1);
    std::size_t callbacks = 0;
    std::size_t terminals = 0;
    require(registry.add_resume("request-resume", "workspace-id",
        [&](const grpc::Status& status, const proto::ServerMessage& message) {
            require(status.ok(), "Resume registration unexpectedly failed.");
            ++callbacks;
            if (JobProtocol::is_terminal(message)) ++terminals;
        }), "A persisted request ID could not be registered for resume.");
    const auto requests = registry.requests();
    require(requests.size() == 1 && requests.front().has_resume_request() &&
            requests.front().resume_request().request_ids_size() == 1 &&
            requests.front().resume_request().request_ids(0) == "request-resume",
        "Resume registration reconstructed a SubmitJob instead of ResumeRequest.");
    proto::ServerMessage state;
    auto* summary = state.mutable_resume_state()->add_jobs();
    summary->set_request_id("request-resume");
    summary->set_state(proto::JOB_STATE_COMPLETED);
    require(registry.resolve(state) && registry.size() == 1,
        "ResumeState consumed the request before cached terminal replay.");
    proto::ServerMessage completed;
    completed.set_request_id("request-resume");
    completed.mutable_job_completed()->set_request_id("request-resume");
    require(registry.resolve(completed) && registry.size() == 0 && callbacks == 2 && terminals == 1,
        "Cached terminal replay was not delivered exactly once.");
}

void synchronous_submit_case(std::string_view tool_name, const Json& arguments, bool actions) {
    WorkspaceFixture fixture;
    const auto artifact_root = fixture.pending().parent_path() / "artifacts";
    std::filesystem::create_directories(artifact_root);
    TerminalJobServer server(fixture.pending(), artifact_root, actions);
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "g007-test",
        .process_instance_uuid = "g007-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    std::vector<std::string> progress;
    vibris::mcp::SynchronousJobControl control{
        .progress = [&](const vibris::mcp::SynchronousJobProgress& event) {
            progress.push_back(event.stage);
        },
    };
    const auto outcome = SynchronousJobRunner(client, sources, config()).run(
        tool_name, arguments, server.server_hello(), context, control);
    const auto stats = client.stats();
    client.shutdown();
    server.shutdown();

    const auto& result = std::get<Json>(outcome);
    require(result.at("success") == true && result.at("frame_ids").at(0) == 901 &&
            result.at("timings").at("total_ms") == 17 &&
            result.at("kind") == (actions ? "action_sequence" : "load_and_screenshot"),
        "Synchronous runner returned before or lost the terminal result.");
    if (actions) {
        require(result.at("action_results").size() == 1 &&
                result.at("action_results").at(0).at("action_index") == 1 &&
                result.at("action_results").at(0).at("kind") == "inspect_shader",
            "Explicit shader load changed the public action result index.");
    }
    require(server.valid_submit() && server.submit_jobs() == 1 && server.terminal_writes() == 1,
        "Synchronous runner did not submit one complete job and consume exactly one terminal.");
    require(std::ranges::find(progress, "warming") != progress.end(),
        "The gRPC client discarded JobProgress before the synchronous runner observed it.");
    require(stats.pending_requests == 0 && vibris::mcp::test::pending_has_no_sources(fixture.pending()),
        "Terminal completion left pending registry or source ownership behind.");
}

void synchronous_submit_waits_for_terminal() {
    synchronous_submit_case("vibris_run_recipe", {{"recipe", "load_and_screenshot"}}, false);
    synchronous_submit_case("vibris_run_actions",
        {{"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
         {"configs", Json::array({{{"id", "config"}, {"mode", "preserve"}}})},
         {"actions", Json::array({{{"type", "load_shader"}, {"source", "source"}, {"config", "config"}},
                                  {{"type", "inspect_shader"}}})}}, true);
}

void profile_result_artifact_mapping() {
    const auto context = SceneContextResolver::resolve(config(), presets());
    const std::vector sources{source("88888888-8888-4888-8888-888888888888")};
    const Json arguments{
        {"recipe", "profile"},
        {"frames", 64},
        {"result_csv", true},
        {"converted_units", Json::array({"us", "ms"})},
        {"__vibris_workflow_id", "11111111-2222-4333-8444-555555555555"},
        {"__vibris_case_id", "source--config"},
        {"__vibris_result_kind", "profile_matrix"},
    };
    const auto message = JobProtocol::request(
        "vibris_run_recipe", arguments, config(), context, sources, "request-profile-artifacts");
    const auto& options = message.submit_job().result_artifacts();
    require(message.submit_job().has_result_artifacts() && options.json() && options.csv() &&
            options.kind() == "profile_matrix" && options.converted_units_size() == 2 &&
            options.converted_units(0) == "us" && options.converted_units(1) == "ms" &&
            options.attempt() == 1 && options.previous_attempts().empty() &&
            message.submit_job().has_benchmark_case() &&
            message.submit_job().benchmark_case().workflow_id() == "11111111-2222-4333-8444-555555555555" &&
            message.submit_job().benchmark_case().case_id() == "source--config",
        "Profile result artifact options were not copied into SubmitJob.");
}

struct MetricsRun final {
    ToolOutcome outcome;
    std::vector<std::vector<std::string>> submitted_case_ids;
    std::vector<std::uint32_t> submitted_attempts;
    std::vector<std::size_t> submitted_previous_attempt_counts;
    std::vector<bool> submitted_benchmark_cases;
    std::size_t terminal_writes;
};

MetricsRun synchronous_metrics_jobs(
    const Json& arguments, MetricsJobService::Plans plans,
    std::vector<std::optional<proto::ErrorCode>> job_failures = {}) {
    WorkspaceFixture fixture;
    MetricsJobServer server(fixture.pending(), std::move(plans), std::move(job_failures));
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "g007-metrics-test",
        .process_instance_uuid = "g007-metrics-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    auto outcome = SynchronousJobRunner(client, sources, config()).run(
        "vibris_run_recipe", arguments, server.server_hello(), context);
    const auto stats = client.stats();
    auto submitted_case_ids = server.submitted_case_ids();
    auto submitted_attempts = server.submitted_attempts();
    auto submitted_previous_attempt_counts = server.submitted_previous_attempt_counts();
    auto submitted_benchmark_cases = server.submitted_benchmark_cases();
    const auto terminal_writes = server.terminal_writes();
    client.shutdown();
    server.shutdown();

    require(server.valid_submit(),
        "Metrics fixture did not receive the expected GPU metric actions.");
    require(stats.pending_requests == 0 && vibris::mcp::test::pending_has_no_sources(fixture.pending()),
        "Metrics completion left pending registry or source ownership behind.");
    return {std::move(outcome), std::move(submitted_case_ids), std::move(submitted_attempts),
            std::move(submitted_previous_attempt_counts), std::move(submitted_benchmark_cases), terminal_writes};
}

ToolOutcome synchronous_metrics_job(
    const Json& arguments, std::vector<std::optional<std::string>> metric_payloads) {
    return std::move(synchronous_metrics_jobs(
        arguments, MetricsJobService::Plans{std::move(metric_payloads)}).outcome);
}

std::size_t count_occurrences(const std::string& value, std::string_view needle) {
    std::size_t count = 0;
    std::size_t offset = 0;
    while ((offset = value.find(needle, offset)) != std::string::npos) {
        ++count;
        offset += needle.size();
    }
    return count;
}

void profile_requires_nonempty_gpu_samples() {
    const Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
        {"max_retries", 0},
    };

    const auto missing = synchronous_metrics_job(arguments, {std::nullopt});
    const auto& missing_result = std::get<Json>(missing);
    const auto& missing_case = missing_result.at("cases").at(0);
    require(missing_result.at("success") == false && missing_result.at("status") == "incomplete" &&
            missing_case.at("case_id") == "source--config" &&
            missing_case.at("error").at("error_code") == "NO_GPU_SAMPLES" &&
            missing_case.at("error").at("retryable") == true &&
            missing_case.at("error").at("details").at("reason") == "missing_gpu_metrics_action" &&
            missing_case.at("frames") == 32 && missing_case.at("warmup_frames") == 0,
        "Missing profile metrics did not return structured NO_GPU_SAMPLES.");

    const auto empty_program = synchronous_metrics_job(arguments, {
        R"({"gpuTimings":{},"gpuProgramTimings":[{"program":"begin3_a","statistics":{}}]})",
    });
    const auto& empty_program_case = std::get<Json>(empty_program).at("cases").at(0);
    require(empty_program_case.at("status") == "incomplete" &&
            empty_program_case.at("error").at("error_code") == "NO_GPU_SAMPLES",
        "Program timing metadata without statistics was accepted as a GPU sample.");

    const auto complete = synchronous_metrics_job(
        arguments, {R"({"gpuTimings":{"composite_total":{"avg":7000000}}})"});
    const auto& result = std::get<Json>(complete);
    const auto& complete_case = result.at("cases").at(0);
    require(result.at("success") == true && result.at("kind") == "profile" &&
            result.at("result_detail") == "metrics" && result.at("gpu_timing_unit") == "ns" &&
            complete_case.at("source_id") == "source" && complete_case.at("config_id") == "config" &&
            complete_case.at("metrics").at("gpuTimings").at("composite_total").at("avg") == 7'000'000 &&
            complete_case.at("frames") == 32 && complete_case.at("warmup_frames") == 0,
        "Complete profile metrics were rejected or lost their frame metadata.");
}

void profile_result_detail_contract() {
    const Json base_arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
    };
    const std::optional<std::string> payload =
        R"({"gpuTimings":{"composite_total":{"avg":7000000}}})";

    auto summary_arguments = base_arguments;
    summary_arguments["result_detail"] = "summary";
    const auto summary_outcome = synchronous_metrics_job(summary_arguments, {payload});
    const auto& summary = std::get<Json>(summary_outcome);
    const auto& summary_case = summary.at("cases").at(0);
    require(summary.at("result_detail") == "summary" && summary.at("gpu_timing_unit") == "ns" &&
            summary_case.at("case_id") == "source--config" &&
            summary_case.at("source_id") == "source" && summary_case.at("config_id") == "config" &&
            summary_case.at("status") == "passed" && summary_case.at("error").is_null() &&
            summary_case.at("frames") == 32 && summary_case.at("warmup_frames") == 0 &&
            summary_case.at("metrics").is_null() && !summary.contains("action_results") &&
            !summary_case.contains("action_results") && count_occurrences(summary.dump(), "gpuTimings") == 0 &&
            summary.at("artifacts").size() == 1 &&
            summary.at("artifacts").at(0).at("file_name") == "profile-result.json",
        "Summary profile result did not match the compact case contract.");

    auto metrics_arguments = base_arguments;
    metrics_arguments["result_detail"] = "metrics";
    const auto metrics_outcome = synchronous_metrics_job(metrics_arguments, {payload});
    const auto& metrics = std::get<Json>(metrics_outcome);
    const auto& metrics_case = metrics.at("cases").at(0);
    require(metrics.at("result_detail") == "metrics" && !metrics.contains("action_results") &&
            !metrics_case.contains("action_results") &&
            metrics_case.at("metrics").at("gpuTimings").contains("composite_total") &&
            count_occurrences(metrics.dump(), "gpuTimings") == 1,
        "Metrics profile result duplicated or omitted GPU timings.");

    auto full_arguments = base_arguments;
    full_arguments["result_detail"] = "full";
    const auto full_outcome = synchronous_metrics_job(full_arguments, {payload});
    const auto& full = std::get<Json>(full_outcome);
    const auto& full_case = full.at("cases").at(0);
    require(full.at("result_detail") == "full" && full.contains("timings") &&
            full.contains("manifest_path") && !full.contains("action_results") &&
            full_case.at("action_results").size() == 1 &&
            full_case.at("action_results").at(0).at("kind") == "load_shader" &&
            full_case.at("metrics").at("gpuTimings").contains("composite_total") &&
            count_occurrences(full.dump(), "gpuTimings") == 1,
        "Full profile result duplicated GPU timings or lost non-metric action details.");
}

void profile_metric_filters_and_converted_units() {
    const Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
        {"metric_filter", Json::array({"composite*", "begin3_a"})},
        {"statistics", Json::array({"avg"})},
        {"converted_units", Json::array({"us", "ms"})},
        {"result_csv", true},
    };
    const auto outcome = synchronous_metrics_job(arguments, {
        R"({"gpuTimings":{"composite18_total":{"avg":7000000,"p50":6800000},"begin3_a":{"avg":103381,"p95":110000},"shadowcomp0":{"avg":120000}}})",
    });
    const auto& result = std::get<Json>(outcome);
    const auto& timings = result.at("cases").at(0).at("metrics").at("gpuTimings");
    require(timings.size() == 2 && timings.contains("composite18_total") && timings.contains("begin3_a") &&
            !timings.contains("shadowcomp0"),
        "Profile metric wildcard filtering returned an unrequested pass.");
    const auto& composite = timings.at("composite18_total");
    require(composite.size() == 3 && composite.at("avg") == 7'000'000 &&
            composite.at("avg_us") == 7'000.0 && composite.at("avg_ms") == 7.0 &&
            !composite.contains("p50") && result.at("gpu_timing_unit") == "ns" &&
            result.at("metric_filter") == arguments.at("metric_filter") &&
            result.at("statistics") == arguments.at("statistics") &&
            result.at("artifacts").size() == 2 &&
            result.at("artifacts").at(1).at("file_name") == "profile-result.csv",
        "Profile statistic filtering, raw nanoseconds, conversions, or artifact paths were lost.");
}

void profile_program_timings_filter_by_program_and_source_identity() {
    Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
        {"metric_filter", Json::array({"GenerateSkyViewLUT.comp.glsl"})},
        {"statistics", Json::array({"avg"})},
        {"converted_units", Json::array({"us"})},
    };
    const Json payload{
        {"gpuTimings", Json::object()},
        {"gpuTimingScopes", Json::array()},
        {"gpuProgramTimings", Json::array({
            {{"metric", "begin3_compute"}, {"kind", "program"}, {"program", "begin3"},
             {"stage", "compute"}, {"source", "CloudAmbientSample.comp.glsl"},
             {"defines", Json::object()}, {"dispatch", "direct:1x1x1"},
             {"framework_pass", "begin3"}, {"compatibility_metric", "begin3_compute"},
             {"statistics", {{"avg", 100'000}, {"p95", 120'000}}}},
            {{"metric", "begin3_a_compute"}, {"kind", "program"}, {"program", "begin3_a"},
             {"stage", "compute"}, {"source", "GenerateSkyViewLUT.comp.glsl"},
             {"defines", {{"SKY_VIEW_SAMPLES", "32"}}}, {"dispatch", "direct:120x68x1"},
             {"framework_pass", "begin3"}, {"compatibility_metric", "begin3_compute"},
             {"statistics", {{"avg", 300'000}, {"p95", 330'000}}}},
        })},
    };

    const auto source_outcome = synchronous_metrics_job(arguments, {payload.dump()});
    const auto& source_metrics = std::get<Json>(source_outcome).at("cases").at(0).at("metrics");
    const auto& source_programs = source_metrics.at("gpuProgramTimings");
    require(source_metrics.at("gpuTimings").empty() && source_metrics.at("gpuTimingScopes").empty() &&
            source_programs.size() == 1 && source_programs.at(0).at("program") == "begin3_a" &&
            source_programs.at(0).at("source") == "GenerateSkyViewLUT.comp.glsl" &&
            source_programs.at(0).at("statistics").at("avg") == 300'000 &&
            source_programs.at(0).at("statistics").at("avg_us") == 300.0 &&
            !source_programs.at(0).at("statistics").contains("p95"),
        "Source identity filtering did not isolate GenerateSkyViewLUT program timing.");

    arguments["metric_filter"] = Json::array({"begin3_a"});
    const auto program_outcome = synchronous_metrics_job(arguments, {payload.dump()});
    const auto& program_timings = std::get<Json>(program_outcome)
        .at("cases").at(0).at("metrics").at("gpuProgramTimings");
    require(program_timings.size() == 1 && program_timings.at(0).at("program") == "begin3_a",
        "Program identity filtering collapsed begin3_a into the begin3 wrapper.");
}

void profile_matrix_reports_incomplete_cases() {
    const Json arguments{
        {"recipe", "profile_matrix"},
        {"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
        {"configs", Json::array({
            {{"id", "success"}, {"values", Json::object()}},
            {{"id", "empty"}, {"values", Json::object()}},
            {{"id", "null"}, {"values", Json::object()}},
            {{"id", "missing"}, {"values", Json::object()}},
            {{"id", "failed"}, {"values", Json::object()}},
        })},
        {"matrix", {{"sources", Json::array({"source"})},
                    {"configs", Json::array({"success", "empty", "null", "missing", "failed"})}}},
        {"warmup_frames", 0},
        {"frames", 64},
        {"max_retries", 0},
    };
    const auto outcome = synchronous_metrics_job(arguments, {
        R"({"gpuTimings":{"composite_total":{"avg":7000000}}})",
        R"({"gpuTimings":{}})",
        R"(null)",
        std::nullopt,
        R"({"success":false,"error_code":"CAPTURE_FAILED","message":"metrics failed"})",
    });
    const auto& result = std::get<Json>(outcome);
    require(result.at("success") == false && result.at("status") == "incomplete" &&
            result.at("requested_cases") == 5 && result.at("completed_cases") == 2 &&
            result.at("cases_with_metrics") == 1 && result.at("missing_cases") == 4 &&
            result.at("failed_cases") == 1 && result.at("retried_cases") == 0 &&
            result.at("passed") == 1 && result.at("failed") == 1 && result.at("incomplete") == 3,
        "Profile matrix completeness counters did not fail closed.");

    const auto& cases = result.at("cases");
    require(cases.size() == 5 && cases.at(0).at("case_id") == "source--success" &&
            cases.at(0).at("source_id") == "source" && cases.at(0).at("config_id") == "success" &&
            cases.at(0).at("status") == "passed" &&
            cases.at(0).at("metrics").at("gpuTimings").contains("composite_total"),
        "Successful profile case was not attributed to its source/config.");
    for (const std::size_t index : {std::size_t{1}, std::size_t{2}, std::size_t{3}}) {
        require(cases.at(index).at("status") == "incomplete" &&
                cases.at(index).at("error").at("error_code") == "NO_GPU_SAMPLES" &&
                cases.at(index).at("error").at("retryable") == true,
            "Empty profile case was incorrectly reported as passed.");
    }
    require(cases.at(3).at("error").at("details").at("reason") == "missing_gpu_metrics_action" &&
            cases.at(4).at("status") == "failed" &&
            cases.at(4).at("error").at("error_code") == "CAPTURE_FAILED",
        "Missing and explicitly failed profile cases were not distinguished.");
}

void profile_matrix_retries_only_retryable_cases() {
    const Json arguments{
        {"recipe", "profile_matrix"},
        {"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
        {"configs", Json::array({
            {{"id", "completed"}, {"values", Json::object()}},
            {{"id", "exhausted"}, {"values", Json::object()}},
            {{"id", "recovered"}, {"values", Json::object()}},
            {{"id", "runtime-recovered"}, {"values", Json::object()}},
        })},
        {"matrix", {{"sources", Json::array({"source"})},
                    {"configs", Json::array({"completed", "exhausted", "recovered", "runtime-recovered"})}}},
        {"warmup_frames", 0},
        {"frames", 64},
    };
    const std::optional<std::string> samples =
        R"({"gpuTimings":{"composite_total":{"avg":7000000}}})";
    const std::optional<std::string> empty = R"({"gpuTimings":{}})";
    const std::optional<std::string> runtime_failure =
        R"({"success":false,"error_code":"INTERNAL_ERROR","message":"runtime failed"})";
    auto run = synchronous_metrics_jobs(arguments, MetricsJobService::Plans{
        {samples, empty, empty, runtime_failure},
        {empty},
        {empty},
        {samples},
        {samples},
    });
    const auto& result = std::get<Json>(run.outcome);
    const auto& completed = result.at("cases").at(0);
    const auto& exhausted = result.at("cases").at(1);
    const auto& recovered = result.at("cases").at(2);
    const auto& runtime_recovered = result.at("cases").at(3);

    require(result.at("success") == false && result.at("status") == "incomplete" &&
            result.at("requested_cases") == 4 && result.at("passed") == 3 &&
            result.at("incomplete") == 1 && result.at("retried_cases") == 3 &&
            result.at("total_attempts") == 8 && result.at("max_retries") == 2 &&
            result.at("job_attempts").size() == 5 && result.at("artifacts").size() == 5,
        "Profile retry aggregation lost final counters or per-attempt artifacts.");
    require(completed.at("status") == "passed" && completed.at("attempt_count") == 1 &&
            completed.at("retry_exhausted") == false && completed.at("attempts").size() == 1,
        "A completed profile case was retried or lost its single receipt.");
    require(exhausted.at("status") == "incomplete" && exhausted.at("attempt_count") == 3 &&
            exhausted.at("retry_exhausted") == true && exhausted.at("attempts").size() == 3 &&
            exhausted.at("attempts").at(2).at("error").at("error_code") == "NO_GPU_SAMPLES",
        "Exhausted profile retries were not retained as an explicit incomplete result.");
    require(recovered.at("status") == "passed" && recovered.at("attempt_count") == 2 &&
            recovered.at("retry_exhausted") == false && recovered.at("attempts").at(0).at("status") == "incomplete" &&
            recovered.at("attempts").at(1).at("status") == "passed",
        "An empty first attempt did not recover or a preceding exhausted case stopped later work.");
    require(runtime_recovered.at("status") == "passed" && runtime_recovered.at("attempt_count") == 2 &&
            runtime_recovered.at("attempts").at(0).at("error").at("error_code") == "INTERNAL_ERROR" &&
            runtime_recovered.at("attempts").at(0).at("retryable") == true,
        "A retryable runtime action error was not retried to success.");
    require(run.terminal_writes == 5 &&
            run.submitted_case_ids == std::vector<std::vector<std::string>>{
                {"source--completed", "source--exhausted", "source--recovered", "source--runtime-recovered"},
                {"source--exhausted"},
                {"source--exhausted"},
                {"source--recovered"},
                {"source--runtime-recovered"},
            } &&
            run.submitted_attempts == std::vector<std::uint32_t>{1, 2, 3, 2, 2} &&
            run.submitted_previous_attempt_counts == std::vector<std::size_t>{0, 1, 2, 1, 1},
        "Retry submissions reran completed cases or omitted bounded attempt history.");
    require(result.at("artifacts").at(0).at("case_ids").size() == 4 &&
            result.at("artifacts").at(1).at("case_ids") == Json::array({"source--exhausted"}) &&
            result.at("artifacts").at(4).at("attempt") == 2,
        "Per-attempt artifacts were not attributable to their retry case.");
}

void profile_retries_retryable_job_failure() {
    const Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
    };
    const std::optional<std::string> samples =
        R"({"gpuTimings":{"composite_total":{"avg":7000000}}})";
    auto run = synchronous_metrics_jobs(
        arguments,
        MetricsJobService::Plans{{samples}, {samples}},
        {proto::ErrorCode::SERVER_NOT_READY, std::nullopt});
    const auto& result = std::get<Json>(run.outcome);
    const auto& profile_case = result.at("cases").at(0);
    require(result.at("success") == true && result.at("retried_cases") == 1 &&
            result.at("total_attempts") == 2 && result.at("artifacts").size() == 1 &&
            profile_case.at("status") == "passed" && profile_case.at("attempt_count") == 2 &&
            profile_case.at("attempts").at(0).at("error").at("error_code") == "SERVER_NOT_READY" &&
            profile_case.at("attempts").at(0).at("retryable") == true &&
            profile_case.at("attempts").at(1).at("status") == "passed",
        "A retryable terminal job failure did not recover with its attempt receipt intact.");
    require(run.terminal_writes == 2 &&
            run.submitted_case_ids == std::vector<std::vector<std::string>>{
                {"source--config"}, {"source--config"},
            } &&
            run.submitted_attempts == std::vector<std::uint32_t>{1, 2} &&
            run.submitted_previous_attempt_counts == std::vector<std::size_t>{0, 1},
        "Retryable job failure did not submit exactly one bounded single-case retry.");
}

void profile_does_not_retry_nonretryable_failure() {
    const Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
    };
    const std::optional<std::string> compile_failure =
        R"({"success":false,"error_code":"SHADER_COMPILE_FAILED","message":"compile failed"})";
    auto run = synchronous_metrics_jobs(arguments, MetricsJobService::Plans{{compile_failure}});
    const auto& result = std::get<Json>(run.outcome);
    const auto& profile_case = result.at("cases").at(0);
    require(result.at("success") == false && result.at("status") == "completed_with_failures" &&
            result.at("retried_cases") == 0 && result.at("total_attempts") == 1 &&
            profile_case.at("status") == "failed" && profile_case.at("attempt_count") == 1 &&
            profile_case.at("retry_exhausted") == false &&
            profile_case.at("attempts").at(0).at("retryable") == false &&
            run.terminal_writes == 1 && run.submitted_attempts == std::vector<std::uint32_t>{1},
        "A non-retryable profile failure was resubmitted or mislabeled as exhausted.");
}

void profile_resume_preserves_prior_attempts() {
    const Json prior{{"attempt", 1}, {"status", "failed"}, {"retryable", true},
                     {"error", {{"success", false}, {"error_code", "SERVER_OFFLINE"},
                                {"message", "interrupted"}, {"retryable", true}}},
                     {"artifact_ids", Json::array()}};
    const Json arguments{
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
        {"max_retries", 0},
        {"__vibris_previous_attempts", Json::array({prior})},
    };
    const std::optional<std::string> samples =
        R"({"gpuTimings":{"composite_total":{"avg":7000000}}})";
    auto run = synchronous_metrics_jobs(arguments, MetricsJobService::Plans{{samples}});
    const auto& profile_case = std::get<Json>(run.outcome).at("cases").at(0);
    require(profile_case.at("status") == "passed" && profile_case.at("attempt_count") == 2 &&
            profile_case.at("attempts").at(0).at("error").at("error_code") == "SERVER_OFFLINE" &&
            run.submitted_attempts == std::vector<std::uint32_t>{2} &&
            run.submitted_previous_attempt_counts == std::vector<std::size_t>{1},
        "A resumed profile case lost its prior attempt history or reused attempt one.");
}

void profile_resume_recovers_committed_artifact_without_resubmit() {
    WorkspaceFixture fixture;
    MetricsJobServer server(fixture.pending(), MetricsJobService::Plans{});
    const std::string request_id = "99999999-8888-4777-8666-555555555555";
    const auto artifact = std::filesystem::path(server.server_hello().artifact_root()) /
        "workspace-id" / request_id / "profile-result.json";
    vibris::mcp::test::write_file(artifact, Json{
        {"artifact_schema_version", 1},
        {"attempt", 2},
        {"previous_attempts", Json::array({{{"attempt", 1}, {"status", "failed"},
            {"error_code", "SERVER_OFFLINE"}, {"message", "disconnected"}, {"retryable", true}}})},
        {"benchmark_barriers", Json::array()},
        {"raw_action_results", Json::array({
            {{"action_index", 0}, {"case_id", "source--config"}, {"kind", "load_shader"},
             {"result", {{"success", true},
                {"provenance", {{"complete", true}, {"case_hash", "recovered-case-hash"}}}}}},
            {{"action_index", 1}, {"case_id", "source--config"}, {"kind", "get_gpu_metrics"},
             {"result", {{"gpuTimings", {{"composite_total", {{"avg", 7'000'000}}}}}}}},
        })},
    }.dump());
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "checkpoint-recovery-test",
        .process_instance_uuid = "checkpoint-recovery-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    vibris::mcp::SynchronousJobControl control{.resume_request_id = request_id};
    const Json arguments{{"recipe", "profile"}, {"source", {{"kind", "workspace"}}},
                         {"config", Json::object()}, {"warmup_frames", 0}, {"frames", 32},
                         {"max_retries", 0}};
    const auto outcome = SynchronousJobRunner(client, sources, config()).run(
        "vibris_run_recipe", arguments, server.server_hello(), context, control);
    const auto stats = client.stats();
    client.shutdown();
    server.shutdown();

    const auto& result = std::get<Json>(outcome);
    const auto& profile_case = result.at("cases").at(0);
    require(result.at("success") == true && result.at("recovered_from_artifact") == true &&
            profile_case.at("status") == "passed" && profile_case.at("attempt_count") == 2 &&
            profile_case.at("attempts").at(0).at("error").at("error_code") == "SERVER_OFFLINE" &&
            server.resume_requests() == 1 && server.submit_jobs() == 0 && stats.pending_requests == 0,
        "Restart recovery reran a case whose committed profile artifact was already durable.");
}

void profile_matrix_38_cases_requires_all_metrics() {
    Json configs = Json::array();
    Json config_axis = Json::array();
    std::vector<std::optional<std::string>> payloads;
    for (int index = 0; index < 38; ++index) {
        const auto id = "preset-" + std::to_string(index);
        configs.push_back({{"id", id}, {"values", Json::object()}});
        config_axis.push_back(id);
        payloads.emplace_back(R"({"gpuTimings":{"composite_total":{"avg":7000000}}})");
    }
    payloads.back() = std::nullopt;
    const Json arguments{
        {"recipe", "profile_matrix"},
        {"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
        {"configs", std::move(configs)},
        {"matrix", {{"sources", Json::array({"source"})}, {"configs", std::move(config_axis)}}},
        {"warmup_frames", 0},
        {"frames", 64},
        {"max_retries", 0},
    };

    const auto outcome = synchronous_metrics_job(arguments, std::move(payloads));
    const auto& result = std::get<Json>(outcome);
    require(result.at("success") == false && result.at("status") == "incomplete" &&
            result.at("requested_cases") == 38 && result.at("completed_cases") == 37 &&
            result.at("cases_with_metrics") == 37 && result.at("missing_cases") == 1 &&
            result.at("passed") == 37 && result.at("incomplete") == 1 &&
            result.at("cases").at(37).at("status") == "incomplete",
        "A 38-case profile matrix reported success despite a missing metrics case.");
}

void paired_benchmark_runner_uses_isolated_profiles() {
    const Json arguments{
        {"recipe", "benchmark_ab"},
        {"baseline", {{"kind", "commit"}, {"revision", "HEAD"}}},
        {"candidate", {{"kind", "workspace"}}},
        {"config", Json::object()},
        {"warmup_frames", 0},
        {"frames", 32},
        {"rounds", 2},
        {"control_rounds", 2},
        {"order", "abba"},
        {"statistic", "avg"},
        {"max_retries", 0},
    };
    const auto payload = [](double value) -> std::optional<std::string> {
        return Json{{"gpuTimings", {{"composite_total", {{"avg", value}}}}}}.dump();
    };
    MetricsJobService::Plans plans;
    for (std::size_t round = 0; round < 2; ++round) {
        for (const auto value : {100.0, 80.0, 80.0, 100.0}) plans.push_back({payload(value)});
        for (const auto value : {100.0, 101.0, 101.0, 100.0}) plans.push_back({payload(value)});
    }
    const auto run = synchronous_metrics_jobs(arguments, std::move(plans));
    const auto& result = std::get<Json>(run.outcome);
    const std::vector<std::vector<std::string>> expected_cases{
        {"ab-r01-s1-baseline"}, {"ab-r01-s2-candidate"}, {"ab-r01-s3-candidate"},
        {"ab-r01-s4-baseline"}, {"noise-r01-s1-a"}, {"noise-r01-s2-b"},
        {"noise-r01-s3-b"}, {"noise-r01-s4-a"},
        {"ab-r02-s1-baseline"}, {"ab-r02-s2-candidate"}, {"ab-r02-s3-candidate"},
        {"ab-r02-s4-baseline"}, {"noise-r02-s1-a"}, {"noise-r02-s2-b"},
        {"noise-r02-s3-b"}, {"noise-r02-s4-a"},
    };
    require(result.at("success") == true && result.at("kind") == "benchmark_ab" &&
            result.at("status") == "completed" && result.at("verdict") == "stable" &&
            result.at("requested_measurements") == 16 && result.at("completed_measurements") == 16 &&
            result.at("guards").at("passed") == true &&
            result.at("guards").at("runtime_state_restored") == true &&
            result.at("round_samples").size() == 2 && result.at("control_round_samples").size() == 2 &&
            result.at("comparison_table").size() == 1 && result.at("artifacts").size() == 16,
        "Synchronous paired recipe did not aggregate its isolated profile receipts.");
    require(run.submitted_case_ids == expected_cases && run.terminal_writes == 16 &&
            run.submitted_benchmark_cases.size() == 16 &&
            std::ranges::all_of(run.submitted_benchmark_cases, [](bool value) { return value; }),
        "Paired recipe did not submit the exact ABBA/control order through isolated benchmark cases.");
}

void matrix_expands_named_source_config_product() {
    const auto context = SceneContextResolver::resolve(config(), presets());
    const std::vector prepared{
        source("66666666-6666-4666-8666-666666666666"),
        source("77777777-7777-4777-8777-777777777777"),
    };
    const Json arguments{
        {"sources", Json::array({{{"id", "base"}, {"kind", "commit"}, {"revision", "HEAD~1"}},
                                 {{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "steep"}, {"values", {{"SETTING_PARALLAX_MODE", 1}}}},
                                 {{"id", "spline"}, {"values", {{"SETTING_PARALLAX_MODE", 4}}}}})},
        {"matrix", {{"sources", Json::array({"base", "candidate"})},
                    {"configs", Json::array({"steep", "spline"})}}},
        {"actions", Json::array({{{"type", "get_gpu_metrics"}, {"frames", 64}}})},
    };

    const auto message = JobProtocol::request(
        "vibris_run_matrix", arguments, config(), context, prepared, "request-matrix");
    const auto& job = message.submit_job();

    require(job.sources_size() == 2 && job.shader_configs_size() == 2 &&
            job.actions().actions_size() == 8,
        "Matrix did not keep two sources/configs or expand four cases.");
    require(job.actions().actions(0).load_shader().source_id() == "base" &&
            job.actions().actions(0).load_shader().config_id() == "steep" &&
            job.actions().actions(0).load_shader().continue_on_failure() &&
            job.actions().actions(2).load_shader().config_id() == "spline" &&
            job.actions().actions(4).load_shader().source_id() == "candidate" &&
            job.actions().actions(6).load_shader().case_id() == "candidate--spline",
        "Matrix expansion order or load references changed.");
}

void source_free_runtime_actions_mapping() {
    const auto context = SceneContextResolver::resolve(config(), presets());
    const Json arguments{{"actions", Json::array({
        {{"type", "inspect_shader"}},
        {{"type", "get_gpu_metrics"}, {"frames", 12}},
    })}};

    const auto message = JobProtocol::request(
        "vibris_run_actions", arguments, config(), context, {}, "request-runtime-actions");
    const auto& sequence = message.submit_job().actions();

    require(message.submit_job().sources().empty() && sequence.actions_size() == 2 &&
            sequence.actions(0).has_inspect_shader() &&
            sequence.actions(1).get_gpu_metrics().frames() == 12,
        "Source-free runtime controls were not encoded as one action sequence.");
}

void title_screen_runtime_can_prepare_source_for_world_loading_job() {
    WorkspaceFixture fixture;
    proto::ServerHello hello;
    hello.set_ready(false);
    hello.set_pending_shaders_root(std::filesystem::absolute(fixture.pending()).string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    vibris::mcp::SourceHandler sources(fixture.worktree());

    sources.prepare("vibris_run_recipe", {{"recipe", "load_and_screenshot"}}, hello);
    const auto references = sources.bind_latest("title-screen-request");

    require(references.size() == 1 && !references.front().uuid().empty(),
        "A title-screen runtime blocked the source for the job that loads its preset world.");
}

void synchronous_submit_resumes_after_acceptance() {
    WorkspaceFixture fixture;
    ReconnectServer server(55066, 0);
    proto::ServerHello hello;
    hello.set_ready(true);
    hello.set_pending_shaders_root(std::filesystem::absolute(fixture.pending()).string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:55066",
        .workspace_id = "workspace-id",
        .mcp_version = "g007-resume-test",
        .process_instance_uuid = "g007-resume-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    const auto outcome = SynchronousJobRunner(client, sources, config()).run(
        "vibris_run_recipe", {{"recipe", "load_and_screenshot"}}, hello, context);
    const auto stats = client.stats();
    client.shutdown();
    server.shutdown();

    require(std::get<Json>(outcome).at("success") == true && stats.pending_requests == 0,
        "Resumed synchronous request did not return its terminal result.");
    require(server.submit_jobs() == 1 && server.resume_requests() == 1 && server.duplicate_submits() == 0,
        "Synchronous reconnect duplicated SubmitJob instead of resuming the accepted request.");
}

void synchronous_submit_has_local_total_deadline() {
    WorkspaceFixture fixture;
    DeadlineJobServer server(fixture.pending());
    proto::ServerHello hello;
    hello.set_ready(true);
    hello.set_pending_shaders_root(std::filesystem::absolute(fixture.pending()).string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "g007-deadline-test",
        .process_instance_uuid = "g007-deadline-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    const auto outcome = SynchronousJobRunner(client, sources, config(), 75ms).run(
        "vibris_run_recipe", {{"recipe", "load_and_screenshot"}}, hello, context);
    const auto stats = client.stats();
    client.shutdown();
    server.shutdown();

    const auto& failure = std::get<ToolFailure>(outcome);
    require(failure.code == "EXECUTION_TIMEOUT" && failure.details.at("request_accepted") == true &&
            failure.details.at("resume_required") == true && stats.pending_requests == 0,
        "Local synchronous deadline did not retire its pending request.");
    require(vibris::mcp::test::pending_has_no_sources(fixture.pending()),
        "Local synchronous deadline stranded source ownership.");
}

void profile_accepted_timeout_requires_resume_before_retry() {
    WorkspaceFixture fixture;
    DeadlineJobServer server(fixture.pending());
    proto::ServerHello hello;
    hello.set_ready(true);
    hello.set_pending_shaders_root(std::filesystem::absolute(fixture.pending()).string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "profile-resume-required-test",
        .process_instance_uuid = "profile-resume-required-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    const Json arguments{{"recipe", "profile"}, {"source", {{"kind", "workspace"}}},
                         {"config", Json::object()}, {"warmup_frames", 0}, {"frames", 32},
                         {"max_retries", 2}};
    const auto outcome = SynchronousJobRunner(client, sources, config(), 75ms).run(
        "vibris_run_recipe", arguments, hello, context);
    client.shutdown();
    server.shutdown();

    const auto& result = std::get<Json>(outcome);
    const auto& profile_case = result.at("cases").at(0);
    require(result.at("total_attempts") == 1 && profile_case.at("attempt_count") == 1 &&
            profile_case.at("retry_exhausted") == false &&
            profile_case.at("error").at("error_code") == "EXECUTION_TIMEOUT" &&
            profile_case.at("error").at("details").at("resume_required") == true,
        "An accepted profile timeout was resubmitted before its request ID could be resumed.");
}

void synchronous_stop_cancels_inflight_request() {
    WorkspaceFixture fixture;
    DeadlineJobServer server(fixture.pending());
    proto::ServerHello hello;
    hello.set_ready(true);
    hello.set_pending_shaders_root(std::filesystem::absolute(fixture.pending()).string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    vibris::mcp::SourceHandler sources(fixture.worktree());
    vibris::mcp::GrpcClient client({
        .target = "127.0.0.1:" + std::to_string(server.port()),
        .workspace_id = "workspace-id",
        .mcp_version = "workflow-cancel-test",
        .process_instance_uuid = "workflow-cancel-test",
        .pending_request_limit = 1,
        .reconnect_delay = 1ms,
        .unary_deadline = 5s,
    });
    client.start();
    const auto context = SceneContextResolver::resolve(config(), presets());
    std::stop_source stop;
    vibris::mcp::SynchronousJobControl control{.stop = stop.get_token()};
    auto outcome = std::async(std::launch::async, [&] {
        return SynchronousJobRunner(client, sources, config(), 5s).run(
            "vibris_run_recipe", {{"recipe", "load_and_screenshot"}}, hello, context, control);
    });
    std::this_thread::sleep_for(100ms);
    stop.request_stop();
    require(outcome.wait_for(5s) == std::future_status::ready,
        "Workflow cancellation did not wake the synchronous runner.");
    const auto result = outcome.get();
    const auto stats = client.stats();
    client.shutdown();
    server.shutdown();
    require(std::get<ToolFailure>(result).code == "CANCELLED" && stats.pending_requests == 0 &&
            vibris::mcp::test::pending_has_no_sources(fixture.pending()),
        "Workflow cancellation did not retire its request and source ownership.");
}

void cancel_waits_for_dispatched_acceptance() {
    PendingRequestRegistry pending(1);
    proto::ClientMessage request;
    request.set_request_id("accept-cancel-race");
    request.mutable_submit_job()->set_request_id(request.request_id());
    std::mutex gate_mutex;
    std::condition_variable gate;
    bool acceptance_started = false;
    bool release_acceptance = false;
    std::atomic_int stage = 0;
    std::atomic_bool callbacks_overlapped = false;
    require(pending.add(std::move(request), [&](const grpc::Status& status, const proto::ServerMessage& message) {
        if (message.has_job_accepted()) {
            {
                std::unique_lock lock(gate_mutex);
                acceptance_started = true;
                gate.notify_one();
                gate.wait(lock, [&] { return release_acceptance; });
            }
            stage.store(2);
            return;
        }
        callbacks_overlapped.store(stage.load() != 2);
        require(status.error_code() == grpc::StatusCode::DEADLINE_EXCEEDED,
            "Cancel did not deliver the local deadline status.");
        stage.store(3);
    }), "Acceptance/cancel race request was not registered.");

    proto::ServerMessage accepted;
    accepted.set_request_id("accept-cancel-race");
    accepted.mutable_job_accepted()->set_request_id(accepted.request_id());
    std::jthread resolver([&] {
        stage.store(1);
        pending.resolve(accepted);
    });
    {
        std::unique_lock lock(gate_mutex);
        require(gate.wait_for(lock, 2s, [&] { return acceptance_started; }),
            "Acceptance callback did not reach its dispatch gate.");
    }

    std::promise<bool> cancel_result;
    auto cancel_finished = cancel_result.get_future();
    std::jthread canceller([&] {
        cancel_result.set_value(pending.cancel("accept-cancel-race",
            {grpc::StatusCode::DEADLINE_EXCEEDED, "deadline"}));
    });
    const bool cancel_bypassed_acceptance = cancel_finished.wait_for(50ms) == std::future_status::ready;
    {
        std::scoped_lock lock(gate_mutex);
        release_acceptance = true;
    }
    gate.notify_one();
    resolver.join();
    canceller.join();

    require(!cancel_bypassed_acceptance && cancel_finished.get(),
        "Cancel completed before an already-dispatched acceptance callback.");
    require(!callbacks_overlapped.load() && stage.load() == 3 && pending.size() == 0,
        "Acceptance and deadline callbacks were not serialized in ownership order.");
}

void grpc_shutdown_does_not_start_operations_after_cq_shutdown() {
    TempDirectory temp("grpc-shutdown");
    const auto pending = temp.path() / "pending";
    const auto artifacts = temp.path() / "artifacts";
    std::filesystem::create_directories(pending);
    std::filesystem::create_directories(artifacts);
    TerminalJobServer server(pending, artifacts, false);
    for (std::size_t index = 0; index < 128; ++index) {
        std::mutex mutex;
        std::condition_variable completed;
        bool terminal = false;
        bool failed = false;
        vibris::mcp::GrpcClient client({
            .target = "127.0.0.1:" + std::to_string(server.port()),
            .workspace_id = "shutdown-race",
            .mcp_version = "g007-shutdown-test",
            .process_instance_uuid = "g007-shutdown-" + std::to_string(index),
            .pending_request_limit = 1,
            .reconnect_delay = 1ms,
            .unary_deadline = 5s,
        });
        client.start();
        proto::ClientMessage request;
        request.set_request_id("shutdown-" + std::to_string(index));
        request.set_workspace_id("shutdown-race");
        auto* job = request.mutable_submit_job();
        job->set_request_id(request.request_id());
        job->add_sources()->set_uuid("missing-source");
        job->mutable_actions()->add_actions()->mutable_activate_source()->set_source_uuid("missing-source");
        job->mutable_actions()->add_actions()->mutable_reset_temporal_state();
        job->mutable_actions()->add_actions()->mutable_wait_frames()->set_frame_count(1);
        job->mutable_actions()->add_actions()->mutable_take_screenshot()->set_artifact_name("screenshot");
        require(client.submit(std::move(request), [&](const grpc::Status& status,
            const proto::ServerMessage& message) {
            {
                std::scoped_lock lock(mutex);
                failed = failed || !status.ok();
                terminal = terminal || JobProtocol::is_terminal(message);
            }
            completed.notify_one();
        }), "Rapid-shutdown request was not registered.");
        std::unique_lock lock(mutex);
        const bool finished = completed.wait_for(lock, 2s, [&] { return terminal || failed; });
        lock.unlock();
        client.shutdown();
        require(finished && !failed, "Rapid-shutdown fixture did not reach a terminal response.");
    }
    server.shutdown();
}

void completed_mapping() {
    const auto artifact_path = (std::filesystem::path("C:\\vibris-artifacts") / "beauty.png").string();
    const auto manifest_path = (std::filesystem::path("C:\\vibris-artifacts") / "manifest.json").string();
    proto::ServerMessage message;
    message.set_request_id("request-1");
    auto* result = message.mutable_job_completed()->mutable_result();
    result->set_kind(proto::JOB_RESULT_KIND_ACTION_SEQUENCE);
    result->set_manifest_path(manifest_path);
    result->add_frame_ids(73);
    result->add_frame_ids(74);
    auto* timing = result->mutable_timings();
    timing->set_queue_ms(2);
    timing->set_execution_ms(11);
    timing->set_total_ms(13);
    auto* comparison = result->mutable_comparison();
    comparison->set_baseline_label("baseline");
    comparison->set_candidate_label("candidate");
    comparison->set_mean_absolute_error(0.25);
    comparison->set_root_mean_square_error(0.5);
    comparison->set_max_absolute_error(1.0);
    auto* diagnostic = result->add_shader_diagnostics();
    diagnostic->set_severity(proto::DIAGNOSTIC_SEVERITY_WARNING);
    diagnostic->set_file_name("composite.fsh");
    diagnostic->set_line(7);
    diagnostic->set_message("warning-marker");
    auto* artifact = result->add_artifacts();
    artifact->set_artifact_id("artifact-1");
    artifact->set_file_name("beauty.png");
    artifact->set_kind(proto::ARTIFACT_KIND_SCREENSHOT);
    artifact->set_format(proto::ARTIFACT_FORMAT_PNG);
    artifact->set_media_type("image/png");
    artifact->set_byte_size(128);
    artifact->set_path(artifact_path);
    auto* status = result->add_action_results();
    status->set_action_index(1);
    status->set_kind(proto::JOB_ACTION_KIND_INSPECT_SHADER);
    status->set_json(R"({"loaded":true})");
    auto* metrics = result->add_action_results();
    metrics->set_action_index(2);
    metrics->set_kind(proto::JOB_ACTION_KIND_GET_GPU_METRICS);
    metrics->set_json(R"({"p50":1.25})");

    const auto outcome = JobProtocol::terminal(message);
    const auto& mapped = std::get<Json>(outcome);

    require(mapped.at("success") == true && mapped.at("kind") == "action_sequence",
        "JobCompleted did not map to a successful action-sequence result.");
    require(mapped.at("diagnostics").at(0).at("message") == "warning-marker" &&
            mapped.at("timings").at("total_ms") == 13 && mapped.at("frame_ids").size() == 2,
        "Diagnostics, timings, or frame IDs were lost.");
    require(mapped.at("comparison").at("baseline_label") == "baseline" &&
            mapped.at("comparison").at("root_mean_square_error") == 0.5,
        "A/B comparison metrics were lost.");
    require(mapped.at("artifacts").at(0).at("path") == artifact_path &&
            mapped.at("manifest_path") == manifest_path,
        "Absolute artifact or manifest paths were not preserved.");
    require(mapped.at("action_results").size() == 2 &&
            mapped.at("action_results").at(0).at("action_index") == 1 &&
            mapped.at("action_results").at(0).at("kind") == "inspect_shader" &&
            mapped.at("action_results").at(0).at("result").at("loaded") == true &&
            mapped.at("action_results").at(1).at("kind") == "get_gpu_metrics" &&
            mapped.at("action_results").at(1).at("result").at("p50") == 1.25,
        "Ordered runtime action results were not preserved.");
}

void artifact_free_completed_mapping() {
    proto::ServerMessage message;
    message.mutable_job_completed()->mutable_result()->set_kind(proto::JOB_RESULT_KIND_ACTION_SEQUENCE);

    const auto outcome = JobProtocol::terminal(message);
    const auto& mapped = std::get<Json>(outcome);

    require(mapped.at("success") == true && mapped.at("kind") == "action_sequence" &&
            mapped.at("artifacts").empty() && mapped.at("manifest_path") == "",
        "Artifact-free action completion was not mapped as a successful terminal result.");
}

void failed_mapping() {
    proto::ServerMessage message;
    auto* failure = message.mutable_job_failed();
    failure->set_request_id("request-2");
    failure->mutable_error()->set_code(proto::SHADER_COMPILE_FAILED);
    failure->mutable_error()->set_message("compile marker");
    failure->mutable_error()->set_field("recipe");
    failure->mutable_error()->set_log_path("C:\\vibris-artifacts\\shader.log");

    const auto outcome = JobProtocol::terminal(message);
    const auto& mapped = std::get<ToolFailure>(outcome);

    require(mapped.code == "SHADER_COMPILE_FAILED" && mapped.message == "compile marker" &&
            mapped.details.at("field") == "recipe" &&
            mapped.details.at("log_path") == "C:\\vibris-artifacts\\shader.log",
        "JobFailed did not preserve structured compile failure details.");
}

void workspace_artifact_link_mapping() {
    WorkspaceFixture fixture;
    std::filesystem::create_directory(fixture.worktree() / ".vibris");
    const std::string workspace_id = "11111111-1111-4111-8111-111111111111";
    const auto target = fixture.pending().parent_path() / "artifact-store" / workspace_id;
    const auto request = target / "request-directory";
    std::filesystem::create_directories(request);
    const auto manifest = request / "manifest.json";
    const auto artifact = request / "capture.png";
    const auto log = request / "shader.log";
    std::ofstream(manifest) << "{}";
    std::ofstream(artifact) << "png";
    std::ofstream(log) << "log";

    ToolOutcome outcome = Json{{"manifest_path", manifest.string()},
        {"artifacts", Json::array({{{"path", artifact.string()}}})},
        {"diagnostics", Json::array({{{"log_path", log.string()}}})}};
    vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(outcome);

    const auto link = fixture.worktree() / ".vibris" / "artifact";
    std::error_code error;
    require(std::filesystem::equivalent(link, target, error) && !error,
        "Artifact link does not target the workspace ID directory.");
    const auto& mapped = std::get<Json>(outcome);
    require(std::filesystem::path(mapped.at("manifest_path").get<std::string>()) ==
            link / "request-directory" / "manifest.json" &&
            std::filesystem::path(mapped.at("artifacts").at(0).at("path").get<std::string>()) ==
            link / "request-directory" / "capture.png" &&
            std::filesystem::path(mapped.at("diagnostics").at(0).at("log_path").get<std::string>()) ==
            link / "request-directory" / "shader.log",
        "Artifact-bearing paths were not rewritten through .vibris/artifact.");

    error.clear();
    std::filesystem::remove(link, error);
    require(!error, "Unable to remove the test artifact link.");
    const auto stale_target = fixture.pending().parent_path() / "stale-artifact-store";
    std::filesystem::create_directory(stale_target);
    std::filesystem::create_directory_symlink(stale_target, link);
    ToolOutcome stale = Json{{"artifacts", Json::array({{{"path", artifact.string()}}})}};
    vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(stale);
    error.clear();
    require(std::filesystem::equivalent(link, target, error) && !error,
        "A stale artifact directory link was not replaced.");

    error.clear();
    std::filesystem::remove(link, error);
    require(!error, "Unable to remove the replaced artifact link.");
    std::filesystem::create_directory(link);
    ToolOutcome occupied = Json{{"artifacts", Json::array({{{"path", artifact.string()}}})}};
    try {
        vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(occupied);
        throw std::runtime_error("Occupied .vibris/artifact did not fail fast.");
    } catch (const StateError& state_error) {
        require(state_error.code() == "ARTIFACT_LINK_ERROR",
            "Occupied .vibris/artifact returned the wrong error code.");
    }
}

void workspace_artifact_link_concurrent_first_use() {
    WorkspaceFixture fixture;
    std::filesystem::create_directory(fixture.worktree() / ".vibris");
    const std::string workspace_id = "22222222-2222-4222-8222-222222222222";
    const auto target = fixture.pending().parent_path() / "artifact-store" / workspace_id;
    const auto request = target / "request-directory";
    std::filesystem::create_directories(request);
    const auto artifact = request / "capture.png";
    std::ofstream(artifact) << "png";
    ToolOutcome first = Json{{"artifacts", Json::array({{{"path", artifact.string()}}})}};
    ToolOutcome second = first;
    std::exception_ptr first_error;
    std::exception_ptr second_error;
    std::thread first_thread([&] {
        try { vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(first); }
        catch (...) { first_error = std::current_exception(); }
    });
    std::thread second_thread([&] {
        try { vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(second); }
        catch (...) { second_error = std::current_exception(); }
    });
    first_thread.join();
    second_thread.join();
    require(!first_error && !second_error,
        "Concurrent MCP instances did not converge on one workspace artifact link.");
}

void workspace_artifact_link_without_canonical_support() {
    char* configured_root_value = nullptr;
    std::size_t configured_root_length = 0;
    if (_dupenv_s(&configured_root_value, &configured_root_length, "VIBRIS_NONCANONICAL_TEST_ROOT") != 0) {
        throw std::runtime_error("Unable to read VIBRIS_NONCANONICAL_TEST_ROOT.");
    }
    const std::unique_ptr<char, decltype(&std::free)> configured_root(configured_root_value, &std::free);
    if (configured_root == nullptr || configured_root_length <= 1) return;

    const auto root = std::filesystem::path(configured_root.get());
    const std::string workspace_id = "33333333-3333-4333-8333-333333333333";
    const auto target = root / workspace_id;
    const auto request = target / "request-directory";
    std::error_code cleanup_error;
    std::filesystem::remove_all(root, cleanup_error);
    std::filesystem::create_directories(request);
    const auto artifact = request / "capture.png";
    std::ofstream(artifact) << "png";

    try {
        WorkspaceFixture fixture;
        std::filesystem::create_directory(fixture.worktree() / ".vibris");
        ToolOutcome outcome = Json{{"artifacts", Json::array({{{"path", artifact.string()}}})}};
        vibris::mcp::WorkspaceArtifactLink(fixture.worktree(), workspace_id).rewrite(outcome);
        const auto& mapped = std::get<Json>(outcome);
        require(std::filesystem::path(mapped.at("artifacts").at(0).at("path").get<std::string>()) ==
                fixture.worktree() / ".vibris" / "artifact" / "request-directory" / "capture.png",
            "Artifact path on a non-canonical filesystem was not rewritten through the workspace link.");
    } catch (...) {
        std::filesystem::remove_all(root, cleanup_error);
        throw;
    }
    std::filesystem::remove_all(root, cleanup_error);
    require(!cleanup_error, "Unable to remove the non-canonical filesystem fixture.");
}

}

int main() {
    try {
        request_mapping();
        remaining_execution_mappings();
        profile_result_artifact_mapping();
        matrix_expands_named_source_config_product();
        incomplete_preset_rejected();
        default_settings_disambiguates_scene_presets();
        empty_actions_mapping();
        source_free_runtime_actions_mapping();
        progress_does_not_consume_terminal();
        resume_registration_replays_terminal_without_submit();
        synchronous_submit_waits_for_terminal();
        profile_requires_nonempty_gpu_samples();
        profile_result_detail_contract();
        profile_metric_filters_and_converted_units();
        profile_program_timings_filter_by_program_and_source_identity();
        profile_matrix_reports_incomplete_cases();
        profile_matrix_retries_only_retryable_cases();
        profile_retries_retryable_job_failure();
        profile_does_not_retry_nonretryable_failure();
        profile_resume_preserves_prior_attempts();
        profile_resume_recovers_committed_artifact_without_resubmit();
        profile_matrix_38_cases_requires_all_metrics();
        paired_benchmark_runner_uses_isolated_profiles();
        title_screen_runtime_can_prepare_source_for_world_loading_job();
        synchronous_submit_resumes_after_acceptance();
        synchronous_submit_has_local_total_deadline();
        profile_accepted_timeout_requires_resume_before_retry();
        synchronous_stop_cancels_inflight_request();
        cancel_waits_for_dispatched_acceptance();
        grpc_shutdown_does_not_start_operations_after_cq_shutdown();
        completed_mapping();
        artifact_free_completed_mapping();
        failed_mapping();
        workspace_artifact_link_mapping();
        workspace_artifact_link_concurrent_first_use();
        workspace_artifact_link_without_canonical_support();
        std::cout << "PASS SynchronousRecipeResultMapping\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL SynchronousRecipeResultMapping: " << error.what() << '\n';
        return 1;
    }
}