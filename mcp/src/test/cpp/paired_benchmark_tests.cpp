#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using vibris::mcp::Json;
using vibris::mcp::ToolOutcome;

constexpr std::string_view workflow_id = "11111111-2222-4333-8444-555555555555";

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

Json arguments(std::string order = "abba", std::size_t rounds = 2) {
    return {{"recipe", "benchmark_ab"},
            {"baseline", {{"kind", "commit"}, {"revision", "HEAD"}}},
            {"candidate", {{"kind", "workspace"}}},
            {"config", {{"QUALITY", 2}}},
            {"warmup_frames", 4},
            {"frames", 32},
            {"rounds", rounds},
            {"control_rounds", rounds},
            {"order", std::move(order)},
            {"random_seed", 7},
            {"statistic", "avg"},
            {"max_retries", 1}};
}

Json profile_result(const Json& request, double value, std::string config_hash = "config-hash",
    std::string scene_hash = "scene-hash", std::string program_source = "GenerateSkyViewLUT.comp.glsl",
    std::optional<std::size_t> frames = std::nullopt) {
    const auto source_id = request.at("__vibris_source_id").get<std::string>();
    const auto case_id = request.at("__vibris_case_id").get<std::string>();
    const Json metrics{
        {"gpuTimings", {{"composite_total", {{"avg", value}}}}},
        {"gpuProgramTimings", Json::array({
            {{"metric", "begin3_a_compute"}, {"kind", "program"}, {"program", "begin3_a"},
             {"stage", "compute"}, {"source", std::move(program_source)},
             {"defines", {{"SKY_VIEW_SAMPLES", "32"}}}, {"dispatch", "direct:120x68x1"},
             {"framework_pass", "begin3"}, {"compatibility_metric", "begin3_compute"},
             {"statistics", {{"avg", value / 2.0}}}},
        })},
    };
    const Json provenance{
        {"complete", true},
        {"source", {{"identity_sha256", source_id + "-source-hash"}}},
        {"shader", {{"config_sha256", std::move(config_hash)}}},
        {"scene", {{"context_sha256", std::move(scene_hash)}}},
    };
    Json profile_case{{"case_id", case_id},
                      {"source_id", source_id},
                      {"config_id", "config"},
                      {"status", "passed"},
                      {"error", nullptr},
                      {"frames", frames.value_or(request.at("frames").get<std::size_t>())},
                      {"warmup_frames", request.at("warmup_frames")},
                      {"metrics", metrics},
                      {"provenance", provenance},
                      {"barriers", Json::array({{{"stage", "state_restored"}}})},
                      {"attempt_count", 1}};
    return {{"success", true}, {"status", "completed"},
            {"cases", Json::array({std::move(profile_case)})}, {"artifacts", Json::array()}};
}

double stable_value(const Json& request) {
    const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
    const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
    if (phase == "comparison") return variant == "candidate" ? 80.0 : 100.0;
    return variant == "b" ? 101.0 : 100.0;
}

Json stable_run(const Json& request_arguments, std::vector<Json>* requests = nullptr) {
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [requests](const Json& request) -> ToolOutcome {
            if (requests != nullptr) requests->push_back(request);
            return profile_result(request, stable_value(request));
        });
    require(std::holds_alternative<Json>(outcome), "Paired benchmark returned a tool failure.");
    return std::get<Json>(outcome);
}

std::vector<std::string> comparison_order(const std::vector<Json>& requests) {
    std::vector<std::string> result;
    for (const auto& request : requests) {
        if (request.at("__vibris_benchmark_phase") == "comparison") {
            result.push_back(request.at("__vibris_benchmark_variant").get<std::string>());
        }
    }
    return result;
}

const Json& aggregate_row(const Json& result) {
    const auto& table = result.at("comparison_table");
    const auto found = std::ranges::find_if(table, [](const Json& row) {
        return row.at("identity").at("timing_kind") == "aggregate" &&
            row.at("identity").at("metric") == "composite_total";
    });
    if (found == table.end()) {
        throw std::runtime_error("Aggregate comparison row is missing: " + table.dump());
    }
    return *found;
}

void order_strategies_are_balanced_and_reproducible() {
    std::vector<Json> abba_requests;
    const auto abba = stable_run(arguments("abba"), &abba_requests);
    const std::vector<std::string> expected_abba{
        "baseline", "candidate", "candidate", "baseline",
        "baseline", "candidate", "candidate", "baseline",
    };
    require(comparison_order(abba_requests) == expected_abba,
        "ABBA did not preserve its paired order in every round.");
    require(abba.at("requested_measurements") == 16 && abba.at("round_samples").size() == 2 &&
            abba.at("control_round_samples").size() == 2,
        "ABBA did not include four comparison and four control measurements per round.");
    for (const auto& request : abba_requests) {
        require(request.at("__vibris_workflow_id") == std::string(workflow_id),
            "A nested profile omitted the isolation workflow identity.");
        if (request.at("__vibris_benchmark_phase") == "control") {
            require(request.at("source") == arguments().at("baseline") &&
                    request.at("__vibris_source_id") == "baseline",
                "A same-commit control measurement did not use the baseline source.");
        }
    }

    std::vector<Json> abab_requests;
    static_cast<void>(stable_run(arguments("abab"), &abab_requests));
    const std::vector<std::string> expected_abab{
        "baseline", "candidate", "baseline", "candidate",
        "baseline", "candidate", "baseline", "candidate",
    };
    require(comparison_order(abab_requests) == expected_abab,
        "ABAB did not preserve its paired order in every round.");

    std::vector<Json> random_first;
    std::vector<Json> random_second;
    static_cast<void>(stable_run(arguments("randomized"), &random_first));
    static_cast<void>(stable_run(arguments("randomized"), &random_second));
    const auto first_order = comparison_order(random_first);
    require(first_order == comparison_order(random_second),
        "Seeded randomized order was not reproducible.");
    for (std::size_t offset = 0; offset < first_order.size(); offset += 4) {
        const auto baseline = std::count(first_order.begin() + offset, first_order.begin() + offset + 4, "baseline");
        const auto candidate = std::count(first_order.begin() + offset, first_order.begin() + offset + 4, "candidate");
        require(baseline == 2 && candidate == 2,
            "Randomized order did not keep a balanced pair inside each round.");
    }
}

void paired_aggregation_reports_effect_noise_and_confidence() {
    const auto result = stable_run(arguments("abba", 4));
    const auto& row = aggregate_row(result);
    require(result.at("success") == true && result.at("status") == "completed" &&
            result.at("verdict") == "stable" && result.at("guards").at("passed") == true &&
            result.at("guards").at("runtime_state_restored") == true,
        "Stable paired samples did not produce a successful guarded result.");
    require(row.at("baseline_median_ns") == 100.0 && row.at("candidate_median_ns") == 80.0 &&
            row.at("absolute_delta_ns") == -20.0 && row.at("percentage_delta") == -20.0 &&
            row.at("paired_delta_median_ns") == -20.0 && row.at("paired_delta_variance_ns2") == 0.0 &&
            row.at("noise_floor_ns") == 1.0 && row.at("clears_noise_floor") == true &&
            row.at("confidence_interval_95").at("low_ns") == -20.0 &&
            row.at("confidence_interval_95").at("high_ns") == -20.0 &&
            row.at("outlier_rounds").empty() && row.at("verdict") == "stable" &&
            row.at("direction") == "improved",
        "Paired aggregation lost its median-of-runs, delta, variance, confidence, noise, or verdict fields.");
    require(result.at("round_samples").size() == 4 &&
            result.at("round_samples").at(0).at("metrics").at(0).contains("baseline_samples_ns") &&
            result.at("comparison_table").size() == 2,
        "Paired result omitted per-round samples or the compact aggregate/program comparison table.");
}

void measured_noise_floor_rejects_small_effects() {
    const auto request_arguments = arguments("abba", 4);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [](const Json& request) -> ToolOutcome {
            const auto phase = request.at("__vibris_benchmark_phase").get<std::string>();
            const auto variant = request.at("__vibris_benchmark_variant").get<std::string>();
            const auto value = phase == "comparison" ? (variant == "candidate" ? 105.0 : 100.0) :
                (variant == "b" ? 110.0 : 100.0);
            return profile_result(request, value);
        });
    const auto& result = std::get<Json>(outcome);
    const auto& row = aggregate_row(result);
    require(result.at("success") == true && result.at("verdict") == "inconclusive" &&
            row.at("paired_delta_median_ns") == 5.0 && row.at("noise_floor_ns") == 10.0 &&
            row.at("clears_noise_floor") == false && row.at("verdict") == "inconclusive" &&
            row.at("direction") == "unchanged",
        "A candidate effect inside the measured same-commit noise floor was accepted as stable.");
}

void mismatched_frames_config_scene_and_program_identity_fail_closed() {
    const auto request_arguments = arguments("abba", 2);
    const auto outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [](const Json& request) -> ToolOutcome {
            const bool candidate = request.at("__vibris_source_id") == "candidate";
            return profile_result(request, stable_value(request), candidate ? "changed-config" : "config-hash",
                candidate ? "changed-scene" : "scene-hash",
                candidate ? "ChangedProgram.comp.glsl" : "GenerateSkyViewLUT.comp.glsl",
                candidate ? std::optional<std::size_t>{33} : std::nullopt);
        });
    const auto& result = std::get<Json>(outcome);
    std::set<std::string> codes;
    for (const auto& mismatch : result.at("guards").at("mismatches")) {
        codes.insert(mismatch.at("code").get<std::string>());
    }
    require(result.at("success") == false && result.at("status") == "invalid_comparison" &&
            result.at("verdict") == "inconclusive" && result.at("comparison_table").empty() &&
            codes.contains("FRAME_COUNT_MISMATCH") && codes.contains("CONFIG_HASH_MISMATCH") &&
            codes.contains("SCENE_HASH_MISMATCH") && codes.contains("PROGRAM_IDENTITY_MISMATCH"),
        "Paired comparison did not fail closed on frames, effective config, scene, and program identity mismatches.");

    std::size_t calls = 0;
    const auto restore_outcome = vibris::mcp::run_paired_benchmark(
        request_arguments, workflow_id, 19, [&calls](const Json& request) -> ToolOutcome {
            ++calls;
            auto profile = profile_result(request, stable_value(request));
            profile["cases"][0]["barriers"] = Json::array();
            return profile;
        });
    const auto& restore_result = std::get<Json>(restore_outcome);
    std::set<std::string> restore_codes;
    for (const auto& mismatch : restore_result.at("guards").at("mismatches")) {
        restore_codes.insert(mismatch.at("code").get<std::string>());
    }
    require(calls == 1 && restore_result.at("success") == false &&
            restore_result.at("status") == "incomplete" &&
            restore_result.at("requested_measurements") == 16 &&
            restore_result.at("remaining_measurements") == 15 &&
            restore_result.at("guards").at("runtime_state_restored") == false &&
            restore_codes.contains("RUNTIME_STATE_RESTORE_MISSING") &&
            restore_codes.contains("WORKFLOW_HALTED_BEFORE_RESTORATION"),
        "A missing restoration receipt did not halt later paired measurements.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 4> test_cases{{
    {"PairedOrderStrategies", order_strategies_are_balanced_and_reproducible},
    {"PairedAggregation", paired_aggregation_reports_effect_noise_and_confidence},
    {"MeasuredNoiseFloorRejection", measured_noise_floor_rejects_small_effects},
    {"PairedMismatchGuards", mismatched_frames_config_scene_and_program_identity_fail_closed},
}};

} // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 2) {
            const auto found = std::ranges::find_if(test_cases, [&](const auto& value) {
                return value.first == argv[1];
            });
            if (found == test_cases.end()) throw std::runtime_error("unknown paired benchmark scenario");
            found->second();
            std::cout << "PASS " << found->first << '\n';
            return 0;
        }
        for (const auto& [name, test] : test_cases) {
            test();
            std::cout << "PASS " << name << '\n';
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL paired benchmark: " << error.what() << '\n';
        return 1;
    }
}