#include "profile_matrix_workflow.hpp"
#include "workspace_source_fixture.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

using vibris::mcp::Json;
using vibris::mcp::ProfileMatrixCaseExecution;
using vibris::mcp::ProfileMatrixWorkflow;
using vibris::mcp::JobContext;
using vibris::mcp::ToolFailure;
using vibris::mcp::ToolOutcome;
using vibris::mcp::test::TempDirectory;
using vibris::mcp::test::WorkspaceFixture;
using namespace std::chrono_literals;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";
constexpr std::array preset_ids{
    "aerial-perspective-1", "aerial-perspective-2", "aerial-perspective-3", "aerial-perspective-4",
    "frutiger-1", "mirror-room-1", "mirror-room-2", "night-gi-1", "non-cube-1", "parallax-1",
    "raster-jungle-1", "shadow-forest-1", "sky-afternoon-1", "sky-dusk-1", "sky-midnight-1",
    "sky-morning-1", "sky-noon-1", "sky-sunset-1", "spawn",
};

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

JobContext config() {
    return {.workspace_id = std::string(workspace_id),
            .save_id = "shader-test-world",
            .dimension_id = "minecraft:overworld",
            .time_preset_id = "noon",
            .camera_preset_id = "spawn",
            .fov = 70.0,
            .default_warmup_frames = 8};
}

Json matrix(std::size_t count, std::string execution = "sync") {
    Json configs = Json::array();
    Json axis = Json::array();
    for (std::size_t index = 0; index < count; ++index) {
        const auto id = "config-" + std::to_string(index + 1);
        configs.push_back({{"id", id}, {"values", {{"QUALITY", index + 1}}}});
        axis.push_back(id);
    }
    return {{"recipe", "profile_matrix"},
            {"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
            {"configs", std::move(configs)},
            {"matrix", {{"sources", Json::array({"source"})}, {"configs", std::move(axis)}}},
            {"__vibris_scene_context", {{"save_id", "shader-test-world"},
                {"dimension_id", "minecraft:overworld"}, {"time_preset_id", "noon"},
                {"weather_preset_id", "clear"}, {"camera_preset_id", "spawn"}, {"fov", 70.0},
                {"resolution", {{"width", 1920}, {"height", 1080}}},
                {"settings_preset_id", "quality"}}},
            {"__vibris_preset", {{"preset_id", "spawn"}, {"version", "2"}, {"display_name", "Spawn"}}},
            {"warmup_frames", 4},
            {"frames", 16},
            {"execution", std::move(execution)}};
}

Json preset_matrix(std::string_view preset_id) {
    const auto preset = std::string(preset_id);
    return {{"recipe", "profile_matrix"},
            {"sources", Json::array({{{"id", "baseline"}, {"kind", "commit"}, {"revision", "HEAD"}},
                                      {{"id", "candidate"}, {"kind", "workspace"}}})},
            {"configs", Json::array({{{"id", "preserve"}, {"mode", "preserve"}}})},
            {"matrix", {{"sources", Json::array({"baseline", "candidate"})},
                        {"configs", Json::array({"preserve"})}}},
            {"__vibris_scene_context", {{"save_id", "save-" + preset},
                {"dimension_id", "minecraft:overworld"}, {"time_preset_id", preset},
                {"weather_preset_id", "clear"}, {"camera_preset_id", preset}, {"fov", 70.0},
                {"resolution", {{"width", 1920}, {"height", 1080}}},
                {"settings_preset_id", "default"}}},
            {"__vibris_preset", {{"preset_id", preset}, {"version", "2"}, {"display_name", preset},
                {"preset_sha256", std::string(64, 'a')}}},
            {"warmup_frames", 4}, {"frames", 16}, {"max_retries", 2}, {"execution", "sync"}};
}

ToolOutcome success(ProfileMatrixCaseExecution& execution, bool retried = false) {
    require(execution.arguments.at("__vibris_scene_context").at("resolution").at("width") == 1920 &&
            execution.arguments.at("__vibris_preset").at("version") == "2",
        "A profile case did not retain its queue-time scene and preset provenance.");
    const auto case_id = execution.arguments.at("__vibris_case_id").get<std::string>();
    const auto source_id = execution.arguments.at("__vibris_source_id").get<std::string>();
    const auto config_id = execution.arguments.at("__vibris_config_id").get<std::string>();
    auto attempts = execution.arguments.value("__vibris_previous_attempts", Json::array());
    if (retried && attempts.empty()) {
        attempts.push_back({{"attempt", 1}, {"status", "incomplete"}, {"retryable", true},
            {"error", {{"error_code", "NO_GPU_SAMPLES"}, {"message", "simulated empty sample set"}}},
            {"artifact_ids", Json::array()}});
    }
    const auto attempt = attempts.size() + 1;
    attempts.push_back({{"attempt", attempt}, {"status", "passed"}, {"retryable", false},
                        {"error", nullptr}, {"artifact_ids", Json::array()}});
    Json profile_case{{"case_id", case_id},
                      {"source_id", source_id},
                      {"config_id", config_id},
                      {"status", "passed"},
                      {"error", nullptr},
                      {"frames", execution.arguments.at("frames")},
                      {"warmup_frames", execution.arguments.at("warmup_frames")},
                      {"metrics", {{"gpuTimings", {{"composite_total", {{"avg", 7'000'000}}}}},
                          {"gpuProgramTimings", Json::array({{{"metric", "begin3_a_compute"},
                              {"kind", "program"}, {"program", "begin3_a"}, {"stage", "compute"},
                              {"source", "GenerateSkyViewLUT.comp.glsl"},
                              {"framework_pass", "begin3"}, {"compatibility_metric", "begin3_compute"},
                              {"statistics", {{"avg", 103'381}}}}})}}},
                      {"provenance", {{"complete", true},
                          {"scene", {{"preset_id", execution.arguments.at("__vibris_preset").at("preset_id")},
                              {"context", execution.arguments.at("__vibris_scene_context")}}}}},
                      {"attempt_count", attempts.size()},
                      {"retry_exhausted", false},
                      {"attempts", std::move(attempts)}};
    return Json{{"success", true}, {"status", "completed"},
                {"cases", Json::array({std::move(profile_case)})},
                {"artifacts", Json::array()}, {"job_attempts", Json::array()}};
}

ToolOutcome interrupted(ProfileMatrixCaseExecution& execution) {
    const auto error = Json{{"success", false}, {"error_code", "SERVER_OFFLINE"},
                            {"message", "simulated interruption"}, {"retryable", true},
                            {"details", {{"resume_required", true}}}};
    const auto attempt = Json{{"attempt", 1}, {"status", "failed"}, {"retryable", true},
                              {"error", error}, {"artifact_ids", Json::array()}};
    const auto profile_case = Json{{"case_id", execution.arguments.at("__vibris_case_id")},
                                   {"source_id", execution.arguments.at("__vibris_source_id")},
                                   {"config_id", execution.arguments.at("__vibris_config_id")},
                                   {"status", "failed"}, {"error", error},
                                   {"frames", execution.arguments.at("frames")},
                                   {"warmup_frames", execution.arguments.at("warmup_frames")},
                                   {"metrics", nullptr}, {"attempt_count", 1},
                                   {"retry_exhausted", false},
                                   {"attempts", Json::array({attempt})}};
    return Json{{"success", false}, {"status", "completed_with_failures"},
                {"cases", Json::array({profile_case})},
                {"artifacts", Json::array()}, {"job_attempts", Json::array()}};
}

void interruption_after_17_resumes_at_18() {
    WorkspaceFixture workspace;
    std::vector<std::string> successful;
    std::size_t calls = 0;
    ProfileMatrixWorkflow first(workspace.worktree(), std::string(workspace_id),
        [&](ProfileMatrixCaseExecution execution) -> ToolOutcome {
            ++calls;
            execution.progress("request-" + std::to_string(calls), "loading", false);
            execution.progress("request-" + std::to_string(calls), "warming", true);
            execution.progress("request-" + std::to_string(calls), "sampling", true);
            if (calls == 18) {
                execution.progress({}, "retrying", true);
                return interrupted(execution);
            }
            successful.push_back(execution.arguments.at("__vibris_case_id").get<std::string>());
            return success(execution, calls == 5);
        });
    const auto paused = std::get<Json>(first.start(matrix(38), config()));
    const auto job_id = paused.at("job_id").get<std::string>();
    const auto snapshots = workspace.worktree() / ".vibris" / "profile-matrix" / job_id / "sources";
    std::filesystem::path frozen_live;
    for (const auto& entry : std::filesystem::recursive_directory_iterator(snapshots)) {
        if (entry.path().filename() == "live.glsl") frozen_live = entry.path();
    }
    require(!frozen_live.empty() && vibris::mcp::test::read_file(frozen_live) == "live-0",
        "The profile source was not frozen when the matrix was queued.");
    vibris::mcp::test::write_file(workspace.live_file(), "mutated-after-queue");
    require(paused.at("workflow_state") == "paused" && paused.at("receipt_count") == 17 &&
            paused.at("progress").at("completed_cases") == 17 &&
            paused.at("progress").at("current_case_number") == 18,
        "The interrupted 38-case workflow did not checkpoint its first 17 receipts.");

    std::size_t resumed_calls = 0;
    std::optional<std::string> resumed_request;
    std::size_t resumed_previous_attempts = 0;
    ProfileMatrixWorkflow restarted(workspace.worktree(), std::string(workspace_id),
        [&](ProfileMatrixCaseExecution execution) -> ToolOutcome {
            ++resumed_calls;
            if (resumed_calls == 1) {
                resumed_request = execution.resume_request_id;
                resumed_previous_attempts = execution.arguments.value(
                    "__vibris_previous_attempts", Json::array()).size();
            }
            execution.progress("resumed-" + std::to_string(resumed_calls), "loading", false);
            execution.progress("resumed-" + std::to_string(resumed_calls), "warming", true);
            execution.progress("resumed-" + std::to_string(resumed_calls), "sampling", true);
            successful.push_back(execution.arguments.at("__vibris_case_id").get<std::string>());
            return success(execution);
        });
    const auto completed = std::get<Json>(restarted.control({{"recipe", "profile_matrix"},
        {"operation", "resume"}, {"job_id", job_id}, {"execution", "sync"}}));
    require(completed.at("success") == true && completed.at("workflow_state") == "completed" &&
            completed.at("receipt_count") == 38 && completed.at("progress").at("completed_cases") == 38 &&
            completed.at("progress").at("stage") == "completed" && resumed_calls == 21 &&
            resumed_request == "request-18" && resumed_previous_attempts == 0 &&
            completed.at("requested_cases") == 38 && completed.at("cases_with_metrics") == 38 &&
            completed.at("missing_cases") == 0 && completed.at("retried_cases") == 1 &&
            completed.at("total_attempts") == 39 && completed.at("gpu_timing_unit") == "ns",
        "The restarted workflow did not resume exactly at case 18.");
    require(vibris::mcp::test::read_file(frozen_live) == "live-0",
        "Resume reread the mutable workspace instead of the queued source snapshot.");
    std::unordered_map<std::string, std::size_t> counts;
    for (const auto& id : successful) ++counts[id];
    require(counts.size() == 38 && std::ranges::all_of(counts, [](const auto& item) { return item.second == 1; }),
        "Restart recovery duplicated a completed case measurement.");
    for (const auto& receipt : completed.at("cases")) {
        const auto& metrics = receipt.at("metrics");
        require(receipt.at("status") == "passed" && metrics.at("gpuTimings").size() == 1 &&
                metrics.at("gpuProgramTimings").size() == 1 &&
                metrics.at("gpuProgramTimings").at(0).at("program") == "begin3_a" &&
                metrics.at("gpuProgramTimings").at(0).at("source") == "GenerateSkyViewLUT.comp.glsl",
            "A final acceptance receipt passed without complete aggregate/program timing provenance.");
    }
    const auto stages = completed.at("progress_stages").dump();
    require(stages.find("loading") != std::string::npos && stages.find("warming") != std::string::npos &&
            stages.find("sampling") != std::string::npos && stages.find("retrying") != std::string::npos &&
            stages.find("completed") != std::string::npos,
        "Checkpoint progress omitted required workflow stages.");
}

void nineteen_distinct_scene_presets_by_two_sources_produce_38_receipts() {
    WorkspaceFixture workspace;
    std::unordered_map<std::string, std::size_t> receipt_counts;
    std::unordered_map<std::string, std::size_t> source_counts;
    std::unordered_map<std::string, std::size_t> preset_counts;
    std::size_t requested = 0;
    std::size_t with_metrics = 0;
    std::size_t retried = 0;
    bool interruption_injected = false;
    bool retry_injected = false;

    for (const std::string_view preset_id : preset_ids) {
        const auto executor = [&](ProfileMatrixCaseExecution execution) -> ToolOutcome {
            const auto actual_preset = execution.arguments.at("__vibris_preset").at("preset_id").get<std::string>();
            require(actual_preset == preset_id &&
                    execution.arguments.at("__vibris_scene_context").at("camera_preset_id").get<std::string>() ==
                        preset_id &&
                    execution.arguments.at("__vibris_config_id") == "preserve",
                "A release-acceptance case conflated its scene preset with the shader config axis.");
            if (!interruption_injected && receipt_counts.size() == 17) {
                interruption_injected = true;
                execution.progress("interrupted-live-request", "sampling", true);
                return interrupted(execution);
            }
            const auto source_id = execution.arguments.at("__vibris_source_id").get<std::string>();
            const auto key = actual_preset + "\n" + source_id;
            ++receipt_counts[key];
            ++source_counts[source_id];
            ++preset_counts[actual_preset];
            const bool inject_retry = !retry_injected && receipt_counts.size() == 5;
            retry_injected = retry_injected || inject_retry;
            return success(execution, inject_retry);
        };

        Json result;
        {
            ProfileMatrixWorkflow workflow(
                workspace.worktree(), std::string(workspace_id), executor);
            result = std::get<Json>(workflow.start(preset_matrix(preset_id), config()));
        }
        if (result.at("workflow_state") == "paused") {
            require(result.at("receipt_count") == 1 && result.at("progress").at("current_case_number") == 2,
                "The injected release-acceptance interruption did not preserve its first source receipt.");
            ProfileMatrixWorkflow restarted(
                workspace.worktree(), std::string(workspace_id), executor);
            result = std::get<Json>(restarted.control({{"recipe", "profile_matrix"},
                {"operation", "resume"}, {"job_id", result.at("job_id")}, {"execution", "sync"}}));
        }

        require(result.at("success") == true && result.at("requested_cases") == 2 &&
                result.at("receipt_count") == 2 && result.at("missing_cases") == 0 &&
                result.at("gpu_timing_unit") == "ns",
            "A typed scene preset did not finish with exactly two complete source receipts.");
        requested += result.at("requested_cases").get<std::size_t>();
        with_metrics += result.at("cases_with_metrics").get<std::size_t>();
        retried += result.at("retried_cases").get<std::size_t>();
        for (const auto& receipt : result.at("cases")) {
            const auto& metrics = receipt.at("metrics");
            require(receipt.at("status") == "passed" && !metrics.at("gpuTimings").empty() &&
                    metrics.at("gpuProgramTimings").size() == 1 &&
                    metrics.at("gpuProgramTimings").at(0).at("program") == "begin3_a" &&
                    metrics.at("gpuProgramTimings").at(0).at("source") == "GenerateSkyViewLUT.comp.glsl" &&
                    receipt.at("provenance").at("scene").at("preset_id").get<std::string>() == preset_id,
                "A release-acceptance receipt passed without metrics, real program/source identity, or scene proof.");
        }
    }

    require(interruption_injected && retry_injected && requested == 38 && with_metrics == 38 && retried == 1 &&
            receipt_counts.size() == 38 &&
            std::ranges::all_of(receipt_counts, [](const auto& item) { return item.second == 1; }) &&
            source_counts == std::unordered_map<std::string, std::size_t>{{"baseline", 19}, {"candidate", 19}} &&
            preset_counts.size() == 19 &&
            std::ranges::all_of(preset_counts, [](const auto& item) { return item.second == 2; }),
        "The final offline acceptance was not exactly 19 distinct scene presets by two unique source receipts.");
}

void async_status_cancel_and_resume_preserve_receipts() {
    WorkspaceFixture workspace;
    std::mutex mutex;
    std::condition_variable ready;
    bool blocked = false;
    bool block_once = true;
    std::unordered_map<std::string, std::size_t> successful;
    ProfileMatrixWorkflow workflow(workspace.worktree(), std::string(workspace_id),
        [&](ProfileMatrixCaseExecution execution) -> ToolOutcome {
            const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
            execution.progress("request-" + id, "loading", false);
            execution.progress("request-" + id, "warming", true);
            execution.progress("request-" + id, "sampling", true);
            if (id.ends_with("config-2") && block_once) {
                {
                    std::scoped_lock lock(mutex);
                    blocked = true;
                    block_once = false;
                }
                ready.notify_one();
                while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
                return ToolFailure{"CANCELLED", "cancelled fixture case", false};
            }
            ++successful[id];
            return success(execution);
        });
    const auto started = std::get<Json>(workflow.start(matrix(2, "async"), config()));
    const auto job_id = started.at("job_id").get<std::string>();
    {
        std::unique_lock lock(mutex);
        require(ready.wait_for(lock, 5s, [&] { return blocked; }), "Async profile never reached sampling.");
    }
    const auto sampling = std::get<Json>(workflow.control({{"recipe", "profile_matrix"},
        {"operation", "status"}, {"job_id", job_id}}));
    const auto compact_status = workflow.active_status();
    require(sampling.at("workflow_state") == "running" && sampling.at("receipt_count") == 1 &&
            sampling.at("progress").at("current_case_number") == 2 &&
            sampling.at("progress").at("stage") == "sampling" && compact_status.at("active") == true &&
            !compact_status.contains("cases") && !compact_status.contains("artifacts"),
        "Partial status did not expose the current sampling case and completed receipt.");
    const auto cancelled = std::get<Json>(workflow.control({{"recipe", "profile_matrix"},
        {"operation", "cancel"}, {"job_id", job_id}}));
    require(cancelled.at("workflow_state") == "cancelled" && cancelled.at("receipt_count") == 1,
        "Cancellation did not durably return the first completed case receipt.");

    const auto completed = std::get<Json>(workflow.control({{"recipe", "profile_matrix"},
        {"operation", "resume"}, {"job_id", job_id}, {"execution", "sync"}}));
    require(completed.at("success") == true && completed.at("receipt_count") == 2 &&
            successful["source--config-1"] == 1 && successful["source--config-2"] == 1,
        "Resume after cancellation duplicated or lost a completed case.");
}

void wrong_case_identity_is_rejected_without_advancing() {
    WorkspaceFixture workspace;
    std::size_t calls = 0;
    std::string workflow_id;
    ProfileMatrixWorkflow workflow(workspace.worktree(), std::string(workspace_id),
        [&](ProfileMatrixCaseExecution execution) -> ToolOutcome {
            ++calls;
            workflow_id = execution.arguments.at("__vibris_workflow_id").get<std::string>();
            auto response = std::get<Json>(success(execution));
            response.at("cases").front()["case_id"] = "source--config-1024";
            return response;
        });
    const auto paused = std::get<Json>(workflow.start(matrix(3), config()));
    require(paused.at("workflow_state") == "paused" && paused.at("receipt_count") == 0 && calls == 1,
        "A mismatched case receipt advanced the profile matrix.");
    require(paused.at("last_error").at("error_code") == "PROFILE_CHECKPOINT_ERROR",
        "A mismatched case receipt did not fail closed with a checkpoint error.");
    require(workflow_id == paused.at("job_id").get<std::string>(),
        "The isolated case did not carry the durable profile workflow identity.");
}

}

int main() {
    try {
        interruption_after_17_resumes_at_18();
        nineteen_distinct_scene_presets_by_two_sources_produce_38_receipts();
        async_status_cancel_and_resume_preserve_receipts();
        wrong_case_identity_is_rejected_without_advancing();
        std::cout << "PASS ProfileMatrixCheckpointResume\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ProfileMatrixCheckpointResume: " << error.what() << '\n';
        return 1;
    }
}
