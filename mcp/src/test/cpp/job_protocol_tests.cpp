#include "job_protocol.hpp"
#include "pending_request_registry.hpp"

#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace proto = ::vibris::control::v2;
using vibris::mcp::JobContext;
using vibris::mcp::JobProtocol;
using vibris::mcp::Json;
using vibris::mcp::PendingRequestRegistry;
using vibris::mcp::ToolFailure;

constexpr std::string_view workspace_id = "11111111-2222-4333-8444-555555555555";
constexpr std::string_view request_id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

void require(const bool condition, std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

JobContext config() {
	return {.workspace_id = std::string(workspace_id), .save_id = "shader-test-world",
		.dimension_id = "minecraft:overworld", .time_preset_id = "noon",
		.camera_preset_id = "spawn", .fov = 70.0, .default_warmup_frames = 8};
}

proto::SceneContext scene() {
	proto::SceneContext result;
	result.set_save_id("shader-test-world");
	result.set_dimension_id("minecraft:overworld");
	result.set_time_preset_id("noon");
	result.set_camera_preset_id("spawn");
	result.set_fov(70.0);
	return result;
}

proto::PreparedSourceRef source() {
	proto::PreparedSourceRef result;
	result.set_source_uuid("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");
	result.set_requested_revision("workspace");
	result.set_resolved_revision("0123456789abcdef");
	result.set_file_count(1);
	result.set_total_bytes(64);
	result.mutable_origin()->mutable_workspace()->set_display_name("fixture");
	return result;
}

proto::PreparedSourceRef source(std::string uuid) {
	auto result = source();
	result.set_source_uuid(std::move(uuid));
	return result;
}

void strict_v2_request_contains_typed_texture_and_buffer_actions() {
	const Json arguments{{"recipe", "capture_debug_bundle"}, {"source", {{"kind", "workspace"}}},
		{"textures", Json::array({"colortex0"})}, {"buffers", Json::array({"scene_ssbo"})},
		{"preset_id", "quality"}};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	require(request.protocol_version().major() == 2 && request.request_id() == request_id &&
		request.workspace_id() == workspace_id && request.has_submit_job(),
		"JobProtocol did not emit a strict v2 submit envelope");
	const auto& job = request.submit_job().job();
	require(job.job_id() == request_id && job.has_action_sequence() &&
		job.restore_state().on_success() && job.restore_state().on_error(),
		"strict v2 job identity or restoration policy is missing");
	require(job.action_sequence().actions(0).has_load_shader() &&
		job.action_sequence().actions(0).prelude(),
		"recipe-generated shader load was exposed as an ordinary input action");
	bool texture = false;
	bool buffer = false;
	for (const auto& action : job.action_sequence().actions()) {
		if (action.has_dump_texture()) {
			texture = action.dump_texture().resource().logical_name() == "colortex0" &&
				action.dump_texture().format() == proto::ARTIFACT_FORMAT_BIN;
		}
		if (action.has_dump_buffer()) {
			buffer = action.dump_buffer().logical_name() == "scene_ssbo";
		}
	}
	require(texture && buffer, "typed strict-v2 texture/buffer capture actions were not encoded");
}

void matrix_auto_load_is_a_prelude_receipt_action() {
	const Json arguments{
		{"actions", Json::array({{{"type", "wait_frames"}, {"frames", 2}}})},
		{"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
		{"configs", Json::array({{{"id", "quality"}, {"values", {{"SAMPLES", 4}}}}})},
		{"matrix", {{"sources", Json::array({"candidate"})}, {"configs", Json::array({"quality"})}}},
	};
	const std::vector sources{source()};
	const auto request = JobProtocol::request(
		"vibris_run_matrix", arguments, config(), scene(), sources, std::string(request_id));
	const auto& cases = request.submit_job().job().matrix().cases();
	require(cases.size() == 1 && cases[0].actions().actions_size() == 2,
		"matrix case did not contain one auto-load and one requested action");
	require(cases[0].actions().actions(0).has_load_shader() &&
		cases[0].actions().actions(0).prelude() &&
		cases[0].actions().actions(1).has_wait_frames() &&
		!cases[0].actions().actions(1).prelude(),
		"matrix auto-load and requested action were not separated by prelude semantics");
}

void compile_validation_uses_typed_uuid_cases_without_render_actions() {
	const Json arguments{{"recipe", "compile_validate"}, {"preset_id", "quality"},
		{"source", {{"kind", "workspace"}}}, {"config", {{"QUALITY", 2}}},
		{"baseline", {{"kind", "commit"}, {"revision", "HEAD~1"}}},
		{"baseline_config", {{"QUALITY", 1}}}};
	const std::vector sources{
		source("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
		source("cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa"),
	};
	const auto request = JobProtocol::request(
		"vibris_run_recipe", arguments, config(), scene(), sources, std::string(request_id));
	const auto& job = request.submit_job().job();
	require(job.has_compile_validation() && !job.has_action_sequence() && !job.has_matrix(),
		"compile_validate was not encoded as its typed workload");
	require(job.compile_validation().cases_size() == 1 && job.compile_validation().has_baseline(),
		"compile_validate omitted the requested case or baseline");
	require(job.compile_validation().cases(0).source_id() == sources[0].source_uuid() &&
		job.compile_validation().baseline().source_id() == sources[1].source_uuid(),
		"compile_validate used logical labels instead of prepared source UUIDs");
	require(job.compile_validation().cases(0).config().values().at("QUALITY") == "2" &&
		job.compile_validation().baseline().config().values().at("QUALITY") == "1" &&
		job.restore_state().on_success() && job.restore_state().on_error(),
		"compile_validate lost typed configs or mandatory restoration");

	proto::ServerMessage completed;
	auto* terminal = completed.mutable_job_completed();
	terminal->set_job_id(std::string(request_id));
	terminal->set_request_id(std::string(request_id));
	auto* result = terminal->mutable_result()->mutable_compile_validation()->add_cases();
	result->set_case_id("source--config");
	result->mutable_catalog()->set_mapping_sha256(std::string(64, 'a'));
	result->add_added_diagnostics()->set_fingerprint_sha256(std::string(64, 'b'));
	result->mutable_provenance()->set_workspace_id(std::string(workspace_id));
	const auto outcome = JobProtocol::terminal(completed);
	const auto* mapped = std::get_if<Json>(&outcome);
	require(mapped != nullptr && mapped->at("result").at("compile_validation").at("cases").at(0)
		.at("catalog").at("mapping_sha256") == std::string(64, 'a') &&
		mapped->at("result").at("compile_validation").at("cases").at(0)
		.at("added_diagnostics").at(0).at("fingerprint_sha256") == std::string(64, 'b'),
		"compile validation terminal mapping dropped catalog or diagnostic diff data");
}

void recovery_request_has_no_scene_or_source_dependency() {
	const auto request = JobProtocol::request("vibris_run_recipe", {{"recipe", "recover_runtime"}},
		config(), proto::SceneContext{}, {}, std::string(request_id));
	const auto& job = request.submit_job().job();
	require(job.has_recover_runtime() && job.sources().empty() && job.preset_id().empty(),
		"recover_runtime was not encoded as a source-free strict-v2 workload");

	proto::ServerMessage failed;
	failed.set_request_id(std::string(request_id));
	auto* value = failed.mutable_job_failed();
	value->set_job_id(std::string(request_id));
	value->set_request_id(std::string(request_id));
	value->mutable_error()->set_code(proto::ERROR_CODE_RECOVERY_FAILED);
	value->mutable_error()->set_message("manual repair required");
	value->mutable_restoration()->set_status(proto::RECEIPT_STATUS_FAILED);
	value->mutable_restoration()->set_expected_source_uuid("safe-source");
	auto* prelude = value->add_prelude_receipts();
	prelude->set_action_index(0);
	prelude->set_kind(proto::ACTION_KIND_LOAD_SHADER);
	prelude->set_status(proto::RECEIPT_STATUS_OK);
	prelude->mutable_runtime_mutation()->set_source_uuid("safe-source");
	auto* action = value->add_action_receipts();
	action->set_action_index(0);
	action->set_kind(proto::ACTION_KIND_WAIT_FRAMES);
	action->set_status(proto::RECEIPT_STATUS_FAILED);
	action->mutable_error()->set_code(proto::ERROR_CODE_EXECUTION_TIMEOUT);
	const auto outcome = JobProtocol::terminal(failed);
	const auto* error = std::get_if<ToolFailure>(&outcome);
	require(error != nullptr && error->details.contains("restoration") &&
		error->details.at("restoration").at("expected_source_uuid") == "safe-source" &&
		error->details.at("prelude_receipts").size() == 1 &&
		error->details.at("action_receipts").size() == 1 &&
		error->details.at("action_receipts").at(0).at("action_index") == 0,
		"recovery failure mapping dropped restoration or ordered action receipts");
}

void explicit_restore_policy_is_not_overridden() {
	const Json arguments{{"actions", Json::array()},
		{"restore_state", {{"on_success", false}, {"on_error", false}}}};
	const auto request = JobProtocol::request("vibris_run_actions", arguments,
		config(), scene(), {}, std::string(request_id));
	const auto& restore = request.submit_job().job().restore_state();
	require(!restore.on_success() && !restore.on_error(),
		"explicit restore_state policy was replaced by hard-coded defaults");
}

void accepted_request_reconnects_with_resume_only() {
	PendingRequestRegistry registry(2);
	const std::vector sources{source()};
	auto request = JobProtocol::request("vibris_run_recipe",
		{{"recipe", "profile"}, {"source", {{"kind", "workspace"}}}, {"frames", 4}},
		config(), scene(), sources, std::string(request_id));
	std::size_t callbacks = 0;
	bool terminal = false;
	require(registry.add(std::move(request), [&](const grpc::Status& status, const proto::ServerMessage& message) {
		require(status.ok(), "registry callback received transport failure");
		++callbacks;
		terminal = JobProtocol::is_terminal(message);
	}), "registry rejected a valid strict-v2 request");
	proto::ServerMessage accepted;
	accepted.set_request_id(std::string(request_id));
	accepted.mutable_job_accepted()->set_job_id(std::string(request_id));
	accepted.mutable_job_accepted()->set_request_id(std::string(request_id));
	require(registry.resolve(accepted) && callbacks == 1 && !terminal && registry.size() == 1,
		"accepted request was not retained as nonterminal");
	const auto reconnect = registry.requests();
	require(reconnect.size() == 1 && reconnect.front().has_resume_job() &&
		!reconnect.front().has_submit_job() && reconnect.front().resume_job().job_id() == request_id &&
		reconnect.front().request_id() == request_id && reconnect.front().workspace_id() == workspace_id,
		"reconnect blindly resubmitted an accepted side effect instead of issuing ResumeJob");
	proto::ServerMessage completed;
	completed.set_request_id(std::string(request_id));
	completed.mutable_job_completed()->set_job_id(std::string(request_id));
	completed.mutable_job_completed()->set_request_id(std::string(request_id));
	require(registry.resolve(completed) && callbacks == 2 && terminal && registry.size() == 0,
		"terminal completion did not retire the resumed request");
}

void resume_registration_and_terminal_mapping_are_strict_v2() {
	PendingRequestRegistry registry(1);
	bool called = false;
	require(registry.add_resume(std::string(request_id), std::string(workspace_id),
		[&](const grpc::Status&, const proto::ServerMessage&) { called = true; }),
		"explicit resume registration failed");
	const auto requests = registry.requests();
	require(requests.size() == 1 && requests.front().protocol_version().major() == 2 &&
		requests.front().has_resume_job() && requests.front().resume_job().job_id() == request_id,
		"explicit resume registration did not construct strict-v2 ResumeJob");
	proto::ServerMessage failed;
	failed.set_request_id(std::string(request_id));
	failed.mutable_job_failed()->set_job_id(std::string(request_id));
	failed.mutable_job_failed()->set_request_id(std::string(request_id));
	failed.mutable_job_failed()->mutable_error()->set_code(proto::ERROR_CODE_SERVER_RESTARTED);
	failed.mutable_job_failed()->mutable_error()->set_message("restart");
	failed.mutable_job_failed()->mutable_error()->set_retryable(true);
	require(registry.resolve(failed) && called, "resumed terminal failure was not delivered");
	const auto outcome = JobProtocol::terminal(failed);
	const auto* error = std::get_if<ToolFailure>(&outcome);
	require(error != nullptr && error->code == "server_restarted" && error->retryable &&
		error->details.at("request_id").get<std::string>() == request_id,
		"strict-v2 terminal failure mapping lost request identity or retryability");
}

} // namespace

int main() {
	try {
		strict_v2_request_contains_typed_texture_and_buffer_actions();
		matrix_auto_load_is_a_prelude_receipt_action();
		compile_validation_uses_typed_uuid_cases_without_render_actions();
		recovery_request_has_no_scene_or_source_dependency();
		explicit_restore_policy_is_not_overridden();
		accepted_request_reconnects_with_resume_only();
		resume_registration_and_terminal_mapping_are_strict_v2();
		std::cout << "PASS JobProtocolStrictV2Resume\n";
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL JobProtocolStrictV2Resume: " << error.what() << '\n';
		return 1;
	}
}
