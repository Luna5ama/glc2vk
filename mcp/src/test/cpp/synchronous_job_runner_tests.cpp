#include "job_protocol.hpp"
#include "paired_benchmark.hpp"
#include "synchronous_job_fixture.hpp"
#include "synchronous_job_runner.hpp"

#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>

namespace {

using vibris::mcp::JobProtocol;
using vibris::mcp::Json;
using namespace vibris::mcp::test;

void require(const bool condition, const std::string_view message) {
	if (!condition) throw std::runtime_error(std::string(message));
}

Json terminal_json(const proto::ServerMessage& message) {
	auto outcome = JobProtocol::terminal(message);
	const auto* result = std::get_if<Json>(&outcome);
	if (result == nullptr) throw std::runtime_error("strict-v2 completion mapped to a failure");
	return *result;
}

Json profile_arguments() {
	return {{"recipe", "profile"}, {"frames", 32}, {"warmup_frames", 4},
		{"result_detail", "full"}, {"__vibris_case_id", "candidate--default"},
		{"__vibris_source_id", "candidate"}, {"__vibris_config_id", "default"}};
}

void strict_v2_result_rejects_removed_shape() {
	const Json removed{{"success", true}, {"job_id", "job"}, {"request_id", "request"},
		{"result", {{"action_results", Json::array()}}}};
	bool rejected = false;
	try {
		(void)vibris::mcp::detail::normalize_profile_result(removed, profile_arguments(), 8, false);
	} catch (const std::invalid_argument&) {
		rejected = true;
	}
	require(rejected, "strict-v2 normalization accepted the removed action_results shape");

	const auto inspection = vibris::mcp::detail::normalize_action_sequence_result(
		terminal_json(completed_inspection_message()), "inspect");
	require(inspection.at("success") == true && inspection.at("prelude_receipts").size() == 1 &&
		inspection.at("prelude_receipts").front().contains("runtime_mutation") &&
		inspection.at("action_receipts").size() == 1 &&
		inspection.at("action_receipts").front().at("shader_inspection").at("catalog")
			.at("programs").front().at("program_id") == "composite",
		"strict-v2 normalization lost typed runtime mutation or compile-catalog inspection details");
}

void profile_normalization_strict_v2() {
	const auto result = vibris::mcp::detail::normalize_profile_result(
		terminal_json(completed_profile_message()), profile_arguments(), 8, false);
	require(result.at("success") == true && result.at("kind") == "profile" &&
		result.at("cases").size() == 1 && result.at("cases").front().at("status") == "passed",
		"profile normalization did not complete its single strict-v2 case");
	const auto& metrics = result.at("cases").front().at("metrics");
	require(metrics.at("timing_unit") == "ns" && metrics.at("sampled_frames") == 3 &&
		metrics.at("metrics").front().at("metric_id") == "composite_total" &&
		metrics.at("metrics").front().at("p50_ns") == 900 &&
		metrics.at("metrics").front().at("p95_ns") == 1100,
		"profile normalization lost typed GPU timing fields");
	require(result.at("provenance").at("active_source_uuid") == "candidate" &&
		result.at("restoration").at("status") == "RECEIPT_STATUS_OK" &&
		result.at("action_receipts").size() == 1 && result.at("prelude_receipts").size() == 1 &&
		!result.contains("action_results") && !result.at("provenance").contains("complete"),
		"profile normalization synthesized legacy receipt or provenance fields");
}

void matrix_normalization_strict_v2() {
	const Json arguments{{"frames", 32}, {"warmup_frames", 4}, {"result_detail", "full"},
		{"matrix", {{"sources", Json::array({"baseline", "candidate"})},
		{"configs", Json::array({"default"})}}}};
	const auto terminal = terminal_json(completed_matrix_message());
	const auto profile = vibris::mcp::detail::normalize_profile_result(terminal, arguments, 8, true);
	require(profile.at("success") == true && profile.at("cases").size() == 2 &&
		profile.at("cases").at(0).at("metrics").at("metrics").front().at("average_ns") == 1200 &&
		profile.at("cases").at(1).at("metrics").at("metrics").front().at("average_ns") == 1000,
		"profile matrix normalization did not consume MatrixCaseResult receipts");
	const auto matrix = vibris::mcp::detail::normalize_matrix_result(terminal, arguments);
	require(matrix.at("success") == true && matrix.at("requested_cases") == 2 &&
		matrix.at("completed_cases") == 2 && matrix.at("cases").at(0).at("action_receipts").size() == 1 &&
		matrix.at("restoration").at("status") == "RECEIPT_STATUS_OK" &&
		!matrix.contains("action_results"),
		"generic matrix normalization did not preserve strict-v2 typed receipts");

	auto failed_message = completed_matrix_message();
	auto* failed_case = failed_message.mutable_job_completed()->mutable_result()->mutable_matrix()->mutable_cases(0);
	failed_case->set_status(proto::RECEIPT_STATUS_FAILED);
	failed_case->mutable_error()->set_code(proto::ERROR_CODE_NO_GPU_SAMPLES);
	failed_case->mutable_error()->set_message("no samples");
	const auto failed_profile = vibris::mcp::detail::normalize_profile_result(
		terminal_json(failed_message), arguments, 8, true);
	require(failed_profile.at("success") == false && failed_profile.at("passed") == 1 &&
		failed_profile.at("failed") == 1 && failed_profile.at("cases").front().at("status") == "failed",
		"failed MatrixCaseResult was counted as a successful normalized profile case");
}

void visual_normalization_strict_v2() {
	const auto result = vibris::mcp::detail::normalize_action_sequence_result(
		terminal_json(completed_visual_message()), "ab_compare");
	require(result.at("success") == true && result.at("frame_ids") == Json::array({41, 42}) &&
		result.at("comparison").at("passed") == true && result.at("artifacts").size() == 2 &&
		result.at("action_receipts").size() == 3 && result.at("prelude_receipts").size() == 2 &&
		!result.contains("action_results"),
		"visual normalization did not derive its receipt from strict-v2 action details");
	const auto guards = vibris::mcp::visual_comparison_guards(result, true);
	require(guards.at("passed") == true, "strict-v2 visual receipt failed deterministic guards");
}

void run(const std::string_view scenario) {
	if (scenario == "StrictV2ResultShape") return strict_v2_result_rejects_removed_shape();
	if (scenario == "ProfileNormalizationStrictV2") return profile_normalization_strict_v2();
	if (scenario == "MatrixNormalizationStrictV2") return matrix_normalization_strict_v2();
	if (scenario == "VisualNormalizationStrictV2") return visual_normalization_strict_v2();
	if (scenario == "all") {
		strict_v2_result_rejects_removed_shape();
		profile_normalization_strict_v2();
		matrix_normalization_strict_v2();
		visual_normalization_strict_v2();
		return;
	}
	throw std::invalid_argument("unknown scenario");
}

} // namespace

int main(const int argc, char** argv) {
	const std::string scenario = argc > 1 ? argv[1] : "all";
	try {
		run(scenario);
		std::cout << "PASS " << scenario << '\n';
		return 0;
	} catch (const std::exception& error) {
		std::cerr << "FAIL " << scenario << ": " << error.what() << '\n';
		return 1;
	}
}
