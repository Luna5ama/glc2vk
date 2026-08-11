#include "profile_matrix_workflow.hpp"
#include "state_error.hpp"
#include "workspace_source_fixture.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace {

using vibris::mcp::DurableJobStepExecution;
using vibris::mcp::DurableJobWorkflow;
using vibris::mcp::JobContext;
using vibris::mcp::Json;
using vibris::mcp::ToolFailure;
using vibris::mcp::ToolOutcome;
using vibris::mcp::test::WorkspaceFixture;
using namespace std::chrono_literals;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";

void require(const bool condition, std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

JobContext config() {
	return {.workspace_id = std::string(workspace_id),
		.save_id = "shader-test-world", .dimension_id = "minecraft:overworld",
		.time_preset_id = "noon", .camera_preset_id = "spawn", .fov = 70.0,
		.default_warmup_frames = 8};
}

Json scene() {
	return {{"save_id", "shader-test-world"}, {"dimension_id", "minecraft:overworld"},
		{"time_preset_id", "noon"}, {"weather_preset_id", "clear"},
		{"camera_preset_id", "spawn"}, {"fov", 70.0},
		{"resolution", {{"width", 1280}, {"height", 720}}}, {"settings_preset_id", "quality"}};
}

Json matrix(const std::size_t count, std::string execution = "sync") {
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
		{"__vibris_scene_context", scene()}, {"frames", 16}, {"warmup_frames", 4},
		{"execution", std::move(execution)}};
}

ToolOutcome profile_success(const DurableJobStepExecution& execution) {
	const auto case_id = execution.arguments.at("__vibris_case_id").get<std::string>();
	return Json{{"success", true}, {"status", "completed"},
		{"cases", Json::array({{{"case_id", case_id},
			{"source_id", execution.arguments.at("__vibris_source_id")},
			{"config_id", execution.arguments.at("__vibris_config_id")},
			{"status", "passed"}, {"error", nullptr}, {"metrics", {{"gpuTimings", {{"total", {{"avg", 1}}}}}}}}})},
		{"artifacts", Json::array()}};
}

Json wait_terminal(DurableJobWorkflow& workflow, const std::string& job_id) {
	for (int attempt = 0; attempt < 500; ++attempt) {
		auto value = std::get<Json>(workflow.control(
			{{"operation", "query"}, {"job_id", job_id}, {"event_cursor", 0}}));
		const auto state = value.at("workflow_state").get<std::string>();
		if (state == "completed" || state == "paused" || state == "cancelled") return value;
		std::this_thread::sleep_for(2ms);
	}
	throw std::runtime_error("durable workflow did not reach a terminal or resumable state");
}

void interruption_after_17_resumes_at_18() {
	WorkspaceFixture workspace;
	std::set<std::string> completed_ids;
	std::size_t calls = 0;
	DurableJobWorkflow first(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++calls;
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			const auto request = "request-" + std::to_string(calls);
			execution.progress(request, "submitted", false);
			execution.progress(request, "accepted", true);
			if (calls == 18) return ToolFailure{"SERVER_OFFLINE", "simulated accepted interruption", true,
				{{"resume_required", true}}};
			require(completed_ids.insert(id).second, "a case was executed twice before restart");
			return profile_success(execution);
		});
	const auto paused = std::get<Json>(first.start("vibris_run_recipe", matrix(38), config()));
	const auto job_id = paused.at("job_id").get<std::string>();
	const auto root = workspace.worktree() / ".vibris" / "jobs" / job_id;
	require(paused.at("workflow_state") == "paused" &&
		paused.at("progress").at("completed_steps") == 17 &&
		paused.at("progress").at("current_step") == 17 && paused.at("progress").at("eta_ms").is_number(),
		"the first process did not durably stop after 17 of 38 cases");
	require(std::filesystem::is_regular_file(root / "request.json") &&
		std::filesystem::is_regular_file(root / "state.json") &&
		std::filesystem::is_regular_file(root / "events.jsonl") &&
		std::filesystem::is_directory(root / "receipts") &&
		std::filesystem::is_directory(root / "sources") &&
		std::distance(std::filesystem::directory_iterator(root / "receipts"),
			std::filesystem::directory_iterator{}) == 17,
		"the strict-v2 job directory is missing atomic state, events, receipts, or sources");
	const auto immutable_request = vibris::mcp::test::read_file(root / "request.json");
	vibris::mcp::test::write_file(workspace.live_file(), "mutated-after-queue");

	std::size_t resumed_calls = 0;
	std::optional<std::string> first_resume_id;
	DurableJobWorkflow restarted(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++resumed_calls;
			if (resumed_calls == 1) first_resume_id = execution.resume_request_id;
			const auto id = execution.arguments.at("__vibris_case_id").get<std::string>();
			require(completed_ids.insert(id).second, "restart duplicated a completed case");
			return profile_success(execution);
		});
	const auto resumed = std::get<Json>(restarted.control({{"operation", "resume"}, {"job_id", job_id}}));
	require(resumed.at("workflow_state") == "queued" || resumed.at("workflow_state") == "running",
		"resume did not start asynchronously");
	const auto terminal = wait_terminal(restarted, job_id);
	require(terminal.at("workflow_state") == "completed" && resumed_calls == 21 &&
		first_resume_id == "request-18" && completed_ids.size() == 38,
		"restart did not resume accepted case 18 and finish each case exactly once");
	const auto result = std::get<Json>(restarted.control(
		{{"operation", "result"}, {"job_id", job_id}, {"event_cursor", 0}}));
	require(result.at("result").at("requested_cases") == 38 &&
		result.at("result").at("completed_cases") == 38 && result.at("result").at("passed") == 38,
		"the final matrix result did not contain all 38 receipts");
	require(vibris::mcp::test::read_file(root / "request.json") == immutable_request,
		"resume modified the immutable request document");
	require(std::filesystem::is_regular_file(root / "result.json") &&
		std::distance(std::filesystem::directory_iterator(root / "receipts"),
			std::filesystem::directory_iterator{}) == 38,
		"the final result or complete receipt set was not published");
	std::uint64_t previous = 0;
	for (const auto& event : result.at("events")) {
		const auto sequence = event.at("sequence").get<std::uint64_t>();
		require(sequence == previous + 1, "event sequence was not monotonic across restart");
		previous = sequence;
	}
	require(previous == result.at("event_cursor"), "event cursor did not match the append-only log tail");
}

void cancellation_is_truthful_and_resumable() {
	WorkspaceFixture workspace;
	std::mutex mutex;
	bool entered = false;
	bool resumed_accepted = false;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			if (execution.resume_request_id) {
				resumed_accepted = *execution.resume_request_id == "accepted-request";
				return profile_success(execution);
			}
			execution.progress("accepted-request", "accepted", true);
			{
				std::scoped_lock lock(mutex);
				entered = true;
			}
			while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
			return ToolFailure{"CANCELLED", "fixture cancellation", false,
				{{"resume_required", true}}};
		});
	const auto started = std::get<Json>(workflow.start("vibris_run_recipe", matrix(1, "async"), config()));
	const auto job_id = started.at("job_id").get<std::string>();
	for (int attempt = 0; attempt < 500; ++attempt) {
		{
			std::scoped_lock lock(mutex);
			if (entered) break;
		}
		std::this_thread::sleep_for(1ms);
	}
	const auto running = std::get<Json>(workflow.control({{"operation", "query"}, {"job_id", job_id}}));
	require(running.at("cancelable") == true && running.at("resumable") == false &&
		running.at("progress").at("eta_ms").is_null(),
		"running job advertised false cancellation/resume/ETA state");
	const auto cancelled = std::get<Json>(workflow.control({{"operation", "cancel"}, {"job_id", job_id}}));
	require(cancelled.at("workflow_state") == "cancelled" && cancelled.at("resumable") == true &&
		cancelled.at("cancelable") == false && cancelled.at("current_request_accepted") == true,
		"cancel did not preserve the accepted request as resumable");
	static_cast<void>(workflow.control({{"operation", "resume"}, {"job_id", job_id}}));
	const auto completed = wait_terminal(workflow, job_id);
	require(completed.at("workflow_state") == "completed" && resumed_accepted,
		"resume blindly resubmitted instead of resuming the accepted request");
}

void generic_plans_checkpoint_each_case() {
	WorkspaceFixture workspace;
	std::size_t calls = 0;
	DurableJobWorkflow workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++calls;
			return Json{{"success", true}, {"tool", execution.tool_name}, {"case", execution.arguments.at("name")}};
		});
	Json arguments{{"cases", Json::array({{{"name", "compile-a"}}, {{"name", "compile-b"}},
		{{"name", "compile-c"}}})}, {"__vibris_scene_context", scene()}};
	const auto result = std::get<Json>(workflow.start("compile_validate", arguments, config()));
	require(result.at("workflow_state") == "completed" && calls == 3 &&
		result.at("progress").at("completed_steps") == 3,
		"generic compile-like cases were not checkpointed independently");
	const auto request_path = workspace.worktree() / ".vibris" / "jobs" /
		result.at("job_id").get<std::string>() / "request.json";
	auto request = Json::parse(vibris::mcp::test::read_file(request_path));
	request["schema_version"] = 1;
	vibris::mcp::test::write_file(request_path, request.dump(2));
	bool rejected = false;
	try {
		static_cast<void>(workflow.control({{"operation", "query"}, {"job_id", result.at("job_id")}}));
	} catch (const vibris::mcp::StateError& error) {
		rejected = error.code() == "UNSUPPORTED_VERSION";
	}
	require(rejected, "a non-v2 durable request was read instead of failing with UNSUPPORTED_VERSION");
}

void matrix_and_benchmark_use_step_plans() {
	WorkspaceFixture workspace;
	std::size_t matrix_calls = 0;
	DurableJobWorkflow matrix_workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			++matrix_calls;
			require(execution.arguments.at("matrix").at("sources").size() == 1 &&
				execution.arguments.at("matrix").at("configs").size() == 1,
				"durable matrix step was not reduced to one source/config case");
			const auto source_id = execution.arguments.at("matrix").at("sources").front().get<std::string>();
			const auto config_id = execution.arguments.at("matrix").at("configs").front().get<std::string>();
			return Json{{"success", true}, {"cases", Json::array({{{"case_id", source_id + "--" + config_id}}})},
				{"artifacts", Json::array()}};
		});
	Json matrix_arguments{{"sources", Json::array({{{"id", "a"}, {"kind", "workspace"}},
		{{"id", "b"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "low"}}, {{"id", "high"}}})},
		{"matrix", {{"sources", Json::array({"a", "b"})}, {"configs", Json::array({"low", "high"})}}},
		{"actions", Json::array()}, {"__vibris_scene_context", scene()}};
	const auto matrix_result = std::get<Json>(
		matrix_workflow.start("vibris_run_matrix", matrix_arguments, config()));
	require(matrix_result.at("workflow_state") == "completed" && matrix_calls == 4 &&
		matrix_result.at("result").at("cases").size() == 4,
		"durable matrix did not checkpoint and aggregate every selected case");

	std::mutex mutex;
	bool entered = false;
	bool carried_scene = false;
	DurableJobWorkflow benchmark_workflow(workspace.worktree(), std::string(workspace_id),
		[&](DurableJobStepExecution execution) -> ToolOutcome {
			{
				std::scoped_lock lock(mutex);
				entered = true;
				carried_scene = execution.arguments.contains("__vibris_scene_context");
			}
			while (!execution.stop.stop_requested()) std::this_thread::sleep_for(1ms);
			return ToolFailure{"CANCELLED", "fixture cancellation", false};
		});
	Json benchmark{{"recipe", "benchmark_ab"}, {"baseline", {{"kind", "workspace"}}},
		{"candidate", {{"kind", "workspace"}}}, {"frames", 4}, {"rounds", 2},
		{"control_rounds", 2}, {"visual", {{"pixel_error_threshold", 0.0}}},
		{"__vibris_scene_context", scene()}, {"execution", "async"}};
	const auto started = std::get<Json>(
		benchmark_workflow.start("vibris_run_recipe", benchmark, config()));
	for (int attempt = 0; attempt < 500; ++attempt) {
		{
			std::scoped_lock lock(mutex);
			if (entered) break;
		}
		std::this_thread::sleep_for(1ms);
	}
	const auto query = std::get<Json>(benchmark_workflow.control(
		{{"operation", "query"}, {"job_id", started.at("job_id")}}));
	require(query.at("progress").at("total_steps") == 17 &&
		query.at("progress").at("completed_steps") == 0 && query.at("progress").at("eta_ms").is_null() &&
		carried_scene,
		"paired benchmark did not expose 16 measurement/control plus one visual checkpoint");
	static_cast<void>(benchmark_workflow.control(
		{{"operation", "cancel"}, {"job_id", started.at("job_id")}}));
}

} // namespace

int main() {
	try {
		interruption_after_17_resumes_at_18();
		cancellation_is_truthful_and_resumable();
		generic_plans_checkpoint_each_case();
		matrix_and_benchmark_use_step_plans();
		std::cout << "PASS DurableWorkflowCheckpointResume\n";
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL DurableWorkflowCheckpointResume: " << error.what() << '\n';
		return 1;
	}
}
