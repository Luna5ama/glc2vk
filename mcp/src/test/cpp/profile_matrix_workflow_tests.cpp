#include "profile_matrix_workflow.hpp"
#include "workspace_source_fixture.hpp"

#include <algorithm>
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
using vibris::mcp::SessionConfig;
using vibris::mcp::ToolFailure;
using vibris::mcp::ToolOutcome;
using vibris::mcp::test::TempDirectory;
using vibris::mcp::test::WorkspaceFixture;
using namespace std::chrono_literals;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

SessionConfig config() {
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

ToolOutcome success(ProfileMatrixCaseExecution& execution) {
    require(execution.arguments.at("__vibris_scene_context").at("resolution").at("width") == 1920 &&
            execution.arguments.at("__vibris_preset").at("version") == "2",
        "A profile case did not retain its queue-time scene and preset provenance.");
    const auto case_id = execution.arguments.at("__vibris_case_id").get<std::string>();
    const auto source_id = execution.arguments.at("__vibris_source_id").get<std::string>();
    const auto config_id = execution.arguments.at("__vibris_config_id").get<std::string>();
    auto attempts = execution.arguments.value("__vibris_previous_attempts", Json::array());
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
                      {"metrics", {{"gpuTimings", {{"composite_total", {{"avg", 7'000'000}}}}}}},
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
            return success(execution);
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
            resumed_request == "request-18" && resumed_previous_attempts == 0,
        "The restarted workflow did not resume exactly at case 18.");
    require(vibris::mcp::test::read_file(frozen_live) == "live-0",
        "Resume reread the mutable workspace instead of the queued source snapshot.");
    std::unordered_map<std::string, std::size_t> counts;
    for (const auto& id : successful) ++counts[id];
    require(counts.size() == 38 && std::ranges::all_of(counts, [](const auto& item) { return item.second == 1; }),
        "Restart recovery duplicated a completed case measurement.");
    const auto stages = completed.at("progress_stages").dump();
    require(stages.find("loading") != std::string::npos && stages.find("warming") != std::string::npos &&
            stages.find("sampling") != std::string::npos && stages.find("retrying") != std::string::npos &&
            stages.find("completed") != std::string::npos,
        "Checkpoint progress omitted required workflow stages.");
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

}

int main() {
    try {
        interruption_after_17_resumes_at_18();
        async_status_cancel_and_resume_preserve_receipts();
        std::cout << "PASS ProfileMatrixCheckpointResume\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ProfileMatrixCheckpointResume: " << error.what() << '\n';
        return 1;
    }
}