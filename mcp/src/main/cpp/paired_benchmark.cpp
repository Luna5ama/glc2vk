#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <iterator>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <random>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

struct PlannedMeasurement final {
    std::string phase;
    std::size_t round = 0;
    std::size_t slot = 0;
    std::string variant;
    std::string physical_source;
    std::string case_id;
};

struct MetricValue final {
    Json identity;
    double nanoseconds = 0.0;
};

struct Measurement final {
    PlannedMeasurement plan;
    Json profile = nullptr;
    Json profile_case = nullptr;
    std::map<std::string, MetricValue> metrics;
};

struct GuardState final {
    Json mismatches = Json::array();
    std::string config_hash;
    std::string scene_hash;
    std::map<std::string, std::string> source_hashes;
    std::set<std::string> aggregate_identities;
    std::set<std::string> program_identities;
    Json identities = Json::object();
    bool runtime_state_restored = true;
};

std::string padded(std::size_t value) {
    auto result = std::to_string(value);
    if (result.size() < 2) result.insert(result.begin(), 2 - result.size(), '0');
    return result;
}

std::vector<std::string> order_for(
    std::string_view strategy, std::string first, std::string second, std::mt19937& random) {
    if (strategy == "abab") return {first, second, first, second};
    if (strategy == "randomized") {
        std::vector result{first, first, second, second};
        std::shuffle(result.begin(), result.end(), random);
        return result;
    }
    return {first, second, second, first};
}

std::vector<PlannedMeasurement> plan(const Json& arguments) {
    const auto rounds = arguments.value("rounds", std::size_t{3});
    const auto control_rounds = arguments.value("control_rounds", rounds);
    const auto strategy = arguments.value("order", std::string("abba"));
    std::mt19937 random(arguments.value("random_seed", std::uint32_t{0}));
    std::vector<PlannedMeasurement> result;
    result.reserve((rounds + control_rounds) * 4);
    for (std::size_t round = 1; round <= std::max(rounds, control_rounds); ++round) {
        if (round <= rounds) {
            const auto order = order_for(strategy, "baseline", "candidate", random);
            for (std::size_t slot = 0; slot < order.size(); ++slot) {
                result.push_back({"comparison", round, slot + 1, order[slot], order[slot],
                    "ab-r" + padded(round) + "-s" + std::to_string(slot + 1) + "-" + order[slot]});
            }
        }
        if (round <= control_rounds) {
            const auto order = order_for(strategy, "a", "b", random);
            for (std::size_t slot = 0; slot < order.size(); ++slot) {
                result.push_back({"control", round, slot + 1, order[slot], "baseline",
                    "noise-r" + padded(round) + "-s" + std::to_string(slot + 1) + "-" + order[slot]});
            }
        }
    }
    return result;
}

Json profile_arguments(const Json& arguments, const PlannedMeasurement& measurement,
    std::string_view workflow_id, std::size_t default_warmup_frames) {
    Json result{{"recipe", "profile"},
                {"source", arguments.at(measurement.physical_source)},
                {"frames", arguments.at("frames")},
                {"warmup_frames", arguments.value("warmup_frames", default_warmup_frames)},
                {"result_detail", "metrics"},
                {"statistics", Json::array({arguments.value("statistic", std::string("avg"))})},
                {"max_retries", arguments.value("max_retries", std::size_t{2})},
                {"__vibris_case_id", measurement.case_id},
                {"__vibris_source_id", measurement.physical_source},
                {"__vibris_config_id", "config"},
                {"__vibris_result_kind", "benchmark_ab"},
                {"__vibris_workflow_id", workflow_id},
                {"__vibris_benchmark_phase", measurement.phase},
                {"__vibris_benchmark_round", measurement.round},
                {"__vibris_benchmark_slot", measurement.slot},
                {"__vibris_benchmark_variant", measurement.variant}};
    if (arguments.contains("config")) result["config"] = arguments.at("config");
    if (arguments.contains("metric_filter")) result["metric_filter"] = arguments.at("metric_filter");
    if (arguments.contains("__vibris_preset")) {
        result["__vibris_preset"] = arguments.at("__vibris_preset");
    }
    return result;
}

Json failure_json(const ToolFailure& failure) {
    return {{"success", false}, {"error_code", failure.code}, {"message", failure.message},
            {"retryable", failure.retryable}, {"details", failure.details}};
}

Json visual_arguments(const Json& arguments, std::size_t default_warmup_frames) {
    auto thresholds = arguments.at("visual");
    const auto warmup = thresholds.value(
        "warmup_frames", arguments.value("warmup_frames", default_warmup_frames));
    thresholds.erase("warmup_frames");
    Json result{{"recipe", "ab_compare"},
                {"a", {{"label", "baseline"}, {"source", arguments.at("baseline")}}},
                {"b", {{"label", "candidate"}, {"source", arguments.at("candidate")}}},
                {"warmup_frames", warmup},
                {"captures", Json::array({{{"type", "screenshot"}, {"format", "png"}}})},
                {"visual_thresholds", std::move(thresholds)}};
    if (arguments.contains("config")) result["config"] = arguments.at("config");
    if (arguments.contains("__vibris_preset")) {
        result["__vibris_preset"] = arguments.at("__vibris_preset");
    }
    return result;
}

Json visual_receipt(ToolOutcome outcome) {
    if (const auto* failure = std::get_if<ToolFailure>(&outcome)) {
        return {{"requested", true}, {"success", false}, {"status", "failed"},
                {"verdict", "inconclusive"}, {"error", failure_json(*failure)},
                {"comparison", nullptr}, {"artifacts", Json::array()},
                {"guards", {{"passed", false}, {"mismatches", Json::array({
                    {{"code", "VISUAL_EXECUTION_FAILED"},
                     {"message", "The visual comparison job did not complete successfully."}}
                })}}}};
    }
    auto result = std::get<Json>(std::move(outcome));
    auto guards = visual_comparison_guards(result, true);
    const auto comparison = result.find("comparison");
    if (!guards.at("passed").get<bool>()) {
        return {{"requested", true}, {"success", false}, {"status", "invalid"},
                {"verdict", "inconclusive"},
                {"error", {{"success", false}, {"error_code", "INVALID_VISUAL_RECEIPT"},
                    {"message", "The visual comparison receipt failed its deterministic-state guards."},
                    {"retryable", false}, {"details", guards.at("mismatches")}}},
                {"comparison", comparison != result.end() && comparison->is_object()
                    ? Json(*comparison) : Json(nullptr)},
                {"artifacts", result.value("artifacts", Json::array())},
                {"artifact_groups", result.value("artifact_groups", Json::array())},
                {"action_results", result.value("action_results", Json::array())},
                {"benchmark_barriers", result.value("benchmark_barriers", Json::array())},
                {"frame_ids", result.value("frame_ids", Json::array())},
                {"guards", std::move(guards)}};
    }
    const bool passed = comparison->at("passed").get<bool>();
    Json receipt{{"requested", true}, {"success", passed},
                 {"status", passed ? "passed" : "failed"},
                 {"verdict", comparison->at("verdict")},
                 {"error", nullptr}, {"comparison", *comparison},
                 {"artifacts", result.value("artifacts", Json::array())},
                 {"artifact_groups", result.value("artifact_groups", Json::array())},
                 {"action_results", result.value("action_results", Json::array())},
                 {"benchmark_barriers", result.value("benchmark_barriers", Json::array())},
                 {"frame_ids", result.value("frame_ids", Json::array())},
                 {"guards", std::move(guards)}};
    return receipt;
}

Json failed_case(const PlannedMeasurement& measurement, const Json& arguments, Json error,
    std::size_t default_warmup_frames) {
    return {{"case_id", measurement.case_id},
            {"source_id", measurement.physical_source},
            {"config_id", "config"},
            {"status", "failed"},
            {"error", std::move(error)},
            {"frames", arguments.at("frames")},
            {"warmup_frames", arguments.value("warmup_frames", default_warmup_frames)},
            {"metrics", nullptr},
            {"provenance", nullptr},
            {"barriers", Json::array()}};
}

Json first_case(const Json& profile, const PlannedMeasurement& measurement, const Json& arguments,
    std::size_t default_warmup_frames) {
    const auto cases = profile.find("cases");
    if (cases != profile.end() && cases->is_array() && cases->size() == 1 && cases->front().is_object()) {
        return cases->front();
    }
    return failed_case(measurement, arguments,
        {{"success", false}, {"error_code", "MALFORMED_PROFILE_RESULT"},
         {"message", "The nested profile did not return exactly one normalized case."}, {"retryable", false}},
        default_warmup_frames);
}

std::string text_at(const Json& value, std::initializer_list<const char*> path) {
    const Json* current = &value;
    for (const auto* name : path) {
        if (!current->is_object()) return {};
        const auto found = current->find(name);
        if (found == current->end()) return {};
        current = &*found;
    }
    return current->is_string() ? current->get<std::string>() : std::string{};
}

Json program_identity(const Json& timing) {
    Json result{{"timing_kind", "program"}};
    constexpr std::array fields{
        "metric", "program", "stage", "source", "defines", "dispatch", "framework_pass", "compatibility_metric",
    };
    for (const auto* field : fields) {
        const auto found = timing.find(field);
        result[field] = found == timing.end() ? Json(nullptr) : *found;
    }
    return result;
}

std::map<std::string, MetricValue> metric_values(const Json& profile_case, std::string_view statistic) {
    std::map<std::string, MetricValue> result;
    const auto metrics = profile_case.find("metrics");
    if (metrics == profile_case.end() || !metrics->is_object()) return result;
    const auto timings = metrics->value("gpuTimings", Json::object());
    for (const auto& [name, statistics] : timings.items()) {
        if (!statistics.is_object()) continue;
        const auto value = statistics.find(statistic);
        if (value == statistics.end() || !value->is_number()) continue;
        Json identity{{"timing_kind", "aggregate"}, {"metric", name}};
        const auto key = identity.dump();
        result.emplace(key, MetricValue{std::move(identity), value->get<double>()});
    }
    const auto programs = metrics->value("gpuProgramTimings", Json::array());
    for (const auto& timing : programs) {
        if (!timing.is_object()) continue;
        const auto statistics = timing.find("statistics");
        if (statistics == timing.end() || !statistics->is_object()) continue;
        const auto value = statistics->find(statistic);
        if (value == statistics->end() || !value->is_number()) continue;
        auto identity = program_identity(timing);
        const auto key = identity.dump();
        result.emplace(key, MetricValue{std::move(identity), value->get<double>()});
    }
    return result;
}

bool restored(const Json& profile_case) {
    const auto barriers = profile_case.value("barriers", Json::array());
    for (const auto& barrier : barriers) {
        if (barrier.is_object() && barrier.value("stage", std::string{}) == "state_restored") return true;
    }
    return false;
}

void mismatch(GuardState& guards, std::string code, const PlannedMeasurement& measurement,
    std::string field, Json expected, Json actual) {
    guards.mismatches.push_back({{"code", std::move(code)}, {"case_id", measurement.case_id},
        {"phase", measurement.phase}, {"round", measurement.round}, {"slot", measurement.slot},
        {"field", std::move(field)}, {"expected", std::move(expected)}, {"actual", std::move(actual)}});
}

std::set<std::string> identities(const Measurement& measurement, std::string_view kind) {
    std::set<std::string> result;
    for (const auto& [key, metric] : measurement.metrics) {
        if (metric.identity.value("timing_kind", std::string{}) == kind) result.insert(key);
    }
    return result;
}

void validate_guards(const std::vector<Measurement>& measurements, const Json& arguments, GuardState& guards) {
    const auto expected_frames = arguments.at("frames");
    bool reference_metrics = false;
    for (const auto& measurement : measurements) {
        const auto& profile_case = measurement.profile_case;
        if (profile_case.value("case_id", std::string{}) != measurement.plan.case_id) {
            mismatch(guards, "CASE_ID_MISMATCH", measurement.plan, "case_id", measurement.plan.case_id,
                profile_case.value("case_id", Json(nullptr)));
        }
        if (profile_case.value("source_id", std::string{}) != measurement.plan.physical_source) {
            mismatch(guards, "SOURCE_ROLE_MISMATCH", measurement.plan, "source_id",
                measurement.plan.physical_source, profile_case.value("source_id", Json(nullptr)));
        }
        if (!restored(profile_case)) {
            guards.runtime_state_restored = false;
            mismatch(guards, "RUNTIME_STATE_RESTORE_MISSING", measurement.plan,
                "barriers.state_restored", true, false);
        }
        if (profile_case.value("status", std::string{}) != "passed") {
            mismatch(guards, "MEASUREMENT_INCOMPLETE", measurement.plan, "status", "passed",
                profile_case.value("status", std::string("missing")));
            continue;
        }
        const auto provenance = profile_case.find("provenance");
        if (provenance == profile_case.end() || !provenance->is_object() ||
            !provenance->value("complete", false)) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.complete", true, false);
        }
        if (profile_case.value("frames", std::uint64_t{}) != expected_frames.get<std::uint64_t>()) {
            mismatch(guards, "FRAME_COUNT_MISMATCH", measurement.plan, "frames", expected_frames,
                profile_case.value("frames", Json(nullptr)));
        }
        const auto config_hash = text_at(profile_case, {"provenance", "shader", "config_sha256"});
        const auto scene_hash = text_at(profile_case, {"provenance", "scene", "context_sha256"});
        const auto source_hash = text_at(profile_case, {"provenance", "source", "identity_sha256"});
        if (config_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.shader.config_sha256", "non-empty", nullptr);
        } else if (guards.config_hash.empty()) {
            guards.config_hash = config_hash;
        } else if (config_hash != guards.config_hash) {
            mismatch(guards, "CONFIG_HASH_MISMATCH", measurement.plan,
                "provenance.shader.config_sha256", guards.config_hash, config_hash);
        }
        if (scene_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.scene.context_sha256", "non-empty", nullptr);
        } else if (guards.scene_hash.empty()) {
            guards.scene_hash = scene_hash;
        } else if (scene_hash != guards.scene_hash) {
            mismatch(guards, "SCENE_HASH_MISMATCH", measurement.plan,
                "provenance.scene.context_sha256", guards.scene_hash, scene_hash);
        }
        const auto physical = measurement.plan.physical_source;
        if (source_hash.empty()) {
            mismatch(guards, "PROVENANCE_GUARD_MISSING", measurement.plan,
                "provenance.source.identity_sha256", "non-empty", nullptr);
        } else if (!guards.source_hashes.contains(physical)) {
            guards.source_hashes.emplace(physical, source_hash);
        } else if (source_hash != guards.source_hashes.at(physical)) {
            mismatch(guards, "SOURCE_IDENTITY_MISMATCH", measurement.plan,
                "provenance.source.identity_sha256", guards.source_hashes.at(physical), source_hash);
        }

        const auto aggregate = identities(measurement, "aggregate");
        const auto programs = identities(measurement, "program");
        if (!reference_metrics) {
            guards.aggregate_identities = aggregate;
            guards.program_identities = programs;
            for (const auto& [key, metric] : measurement.metrics) guards.identities[key] = metric.identity;
            reference_metrics = true;
        } else {
            if (aggregate != guards.aggregate_identities) {
                mismatch(guards, "METRIC_IDENTITY_MISMATCH", measurement.plan,
                    "gpuTimings", Json(guards.aggregate_identities), Json(aggregate));
            }
            if (programs != guards.program_identities) {
                mismatch(guards, "PROGRAM_IDENTITY_MISMATCH", measurement.plan,
                    "gpuProgramTimings", Json(guards.program_identities), Json(programs));
            }
        }
    }
    if (!reference_metrics || (guards.aggregate_identities.empty() && guards.program_identities.empty())) {
        guards.mismatches.push_back({{"code", "NO_COMPARABLE_METRICS"},
            {"field", "metrics"}, {"expected", "at least one selected timing"}, {"actual", nullptr}});
    }
}

double median(std::vector<double> values) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const auto middle = values.size() / 2;
    return values.size() % 2 == 0 ? (values[middle - 1] + values[middle]) / 2.0 : values[middle];
}

double quantile(std::vector<double> values, double probability) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const auto position = probability * static_cast<double>(values.size() - 1);
    const auto lower = static_cast<std::size_t>(std::floor(position));
    const auto upper = static_cast<std::size_t>(std::ceil(position));
    if (lower == upper) return values[lower];
    const auto fraction = position - static_cast<double>(lower);
    return values[lower] + (values[upper] - values[lower]) * fraction;
}

std::vector<std::size_t> outlier_rounds(const std::vector<double>& values) {
    std::vector<std::size_t> result;
    if (values.size() < 4) return result;
    const auto q1 = quantile(values, 0.25);
    const auto q3 = quantile(values, 0.75);
    const auto iqr = q3 - q1;
    const auto lower = q1 - 1.5 * iqr;
    const auto upper = q3 + 1.5 * iqr;
    for (std::size_t index = 0; index < values.size(); ++index) {
        if (values[index] < lower || values[index] > upper) result.push_back(index + 1);
    }
    return result;
}

double t_critical_95(std::size_t samples) {
    constexpr std::array values{
        0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262,
        2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093,
        2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
    };
    if (samples < 2) return std::numeric_limits<double>::quiet_NaN();
    const auto degrees = samples - 1;
    return degrees < values.size() ? values[degrees] : 1.96;
}

Json confidence_interval(const std::vector<double>& values) {
    if (values.size() < 2) return nullptr;
    const auto mean = std::accumulate(values.begin(), values.end(), 0.0) /
        static_cast<double>(values.size());
    double squared = 0.0;
    for (const auto value : values) squared += (value - mean) * (value - mean);
    const auto variance = squared / static_cast<double>(values.size() - 1);
    const auto margin = t_critical_95(values.size()) * std::sqrt(variance / static_cast<double>(values.size()));
    return {{"low_ns", mean - margin}, {"high_ns", mean + margin},
            {"confidence", 0.95}, {"method", "paired_student_t"}};
}

double sample_variance(const std::vector<double>& values) {
    if (values.size() < 2) return std::numeric_limits<double>::quiet_NaN();
    const auto mean = std::accumulate(values.begin(), values.end(), 0.0) /
        static_cast<double>(values.size());
    double squared = 0.0;
    for (const auto value : values) squared += (value - mean) * (value - mean);
    return squared / static_cast<double>(values.size() - 1);
}

const Measurement& find_measurement(
    const std::vector<Measurement>& measurements, std::string_view phase, std::size_t round, std::size_t slot) {
    const auto found = std::ranges::find_if(measurements, [&](const Measurement& measurement) {
        return measurement.plan.phase == phase && measurement.plan.round == round && measurement.plan.slot == slot;
    });
    if (found == measurements.end()) throw std::logic_error("paired benchmark plan lost a measurement");
    return *found;
}

std::vector<double> samples_for(const std::vector<Measurement>& measurements, std::string_view phase,
    std::size_t round, std::string_view variant, std::string_view identity) {
    std::vector<double> result;
    for (const auto& measurement : measurements) {
        if (measurement.plan.phase != phase || measurement.plan.round != round ||
            measurement.plan.variant != variant) continue;
        result.push_back(measurement.metrics.at(std::string(identity)).nanoseconds);
    }
    return result;
}

Json round_samples(const std::vector<Measurement>& measurements, const GuardState& guards,
    std::string_view phase, std::size_t count, std::string_view first, std::string_view second) {
    Json result = Json::array();
    for (std::size_t round = 1; round <= count; ++round) {
        Json order = Json::array();
        Json case_ids = Json::array();
        for (std::size_t slot = 1; slot <= 4; ++slot) {
            const auto& measurement = find_measurement(measurements, phase, round, slot);
            order.push_back(measurement.plan.variant);
            case_ids.push_back(measurement.plan.case_id);
        }
        Json metrics = Json::array();
        for (const auto& [key, identity] : guards.identities.items()) {
            auto first_samples = samples_for(measurements, phase, round, first, key);
            auto second_samples = samples_for(measurements, phase, round, second, key);
            const auto first_median = median(first_samples);
            const auto second_median = median(second_samples);
            Json metric{{"identity", identity},
                        {std::string(first) + "_samples_ns", first_samples},
                        {std::string(second) + "_samples_ns", second_samples},
                        {std::string(first) + "_median_ns", first_median},
                        {std::string(second) + "_median_ns", second_median},
                        {"delta_ns", second_median - first_median}};
            metric["delta_percent"] = first_median == 0.0 ? Json(nullptr) :
                Json((second_median - first_median) / first_median * 100.0);
            metrics.push_back(std::move(metric));
        }
        result.push_back({{"round", round}, {"order", std::move(order)},
            {"case_ids", std::move(case_ids)}, {"metrics", std::move(metrics)}});
    }
    return result;
}

const Json& round_metric(const Json& rounds, std::size_t round, const Json& identity) {
    for (const auto& metric : rounds.at(round - 1).at("metrics")) {
        if (metric.at("identity") == identity) return metric;
    }
    throw std::logic_error("paired benchmark round lost a metric identity");
}

Json comparison_table(const GuardState& guards, const Json& comparison_rounds, const Json& control_rounds) {
    Json table = Json::array();
    for (const auto& [key, identity] : guards.identities.items()) {
        static_cast<void>(key);
        std::vector<double> baseline_medians;
        std::vector<double> candidate_medians;
        std::vector<double> paired_deltas;
        for (std::size_t round = 1; round <= comparison_rounds.size(); ++round) {
            const auto& metric = round_metric(comparison_rounds, round, identity);
            baseline_medians.push_back(metric.at("baseline_median_ns").get<double>());
            candidate_medians.push_back(metric.at("candidate_median_ns").get<double>());
            paired_deltas.push_back(metric.at("delta_ns").get<double>());
        }
        std::vector<double> noise_deltas;
        for (std::size_t round = 1; round <= control_rounds.size(); ++round) {
            noise_deltas.push_back(round_metric(control_rounds, round, identity).at("delta_ns").get<double>());
        }
        std::vector<double> absolute_noise;
        absolute_noise.reserve(noise_deltas.size());
        std::ranges::transform(noise_deltas, std::back_inserter(absolute_noise), [](double value) {
            return std::abs(value);
        });
        const auto baseline = median(baseline_medians);
        const auto candidate = median(candidate_medians);
        const auto absolute_delta = candidate - baseline;
        const auto paired_delta = median(paired_deltas);
        const auto paired_mean = std::accumulate(paired_deltas.begin(), paired_deltas.end(), 0.0) /
            static_cast<double>(paired_deltas.size());
        const auto noise_floor = quantile(absolute_noise, 0.95);
        const auto interval = confidence_interval(paired_deltas);
        const bool confidence_excludes_zero = interval.is_object() &&
            (interval.at("low_ns").get<double>() > 0.0 || interval.at("high_ns").get<double>() < 0.0);
        const bool clears_noise_floor = std::abs(paired_delta) > noise_floor;
        const auto outliers = outlier_rounds(paired_deltas);
        const auto verdict = !outliers.empty() ? "unstable" :
            (clears_noise_floor && confidence_excludes_zero ? "stable" : "inconclusive");
        const auto direction = !clears_noise_floor ? "unchanged" :
            (paired_delta < 0.0 ? "improved" : "regressed");
        Json row{{"identity", identity},
                 {"baseline_median_ns", baseline},
                 {"candidate_median_ns", candidate},
                 {"absolute_delta_ns", absolute_delta},
                 {"percentage_delta", baseline == 0.0 ? Json(nullptr) : Json(absolute_delta / baseline * 100.0)},
                 {"paired_delta_median_ns", paired_delta},
                 {"paired_delta_mean_ns", paired_mean},
                 {"paired_delta_variance_ns2", sample_variance(paired_deltas)},
                 {"confidence_interval_95", interval},
                 {"control_paired_deltas_ns", noise_deltas},
                 {"noise_floor_ns", noise_floor},
                 {"noise_floor_percent", baseline == 0.0 ? Json(nullptr) : Json(noise_floor / baseline * 100.0)},
                 {"clears_noise_floor", clears_noise_floor},
                 {"outlier_rounds", outliers},
                 {"verdict", verdict},
                 {"direction", direction}};
        table.push_back(std::move(row));
    }
    return table;
}

std::string overall_verdict(const Json& table) {
    bool inconclusive = false;
    for (const auto& row : table) {
        const auto verdict = row.at("verdict").get<std::string>();
        if (verdict == "unstable") return "unstable";
        if (verdict == "inconclusive") inconclusive = true;
    }
    return inconclusive || table.empty() ? "inconclusive" : "stable";
}

Json compact_execution(const Measurement& measurement) {
    const auto& profile_case = measurement.profile_case;
    return {{"case_id", measurement.plan.case_id},
            {"phase", measurement.plan.phase},
            {"round", measurement.plan.round},
            {"slot", measurement.plan.slot},
            {"variant", measurement.plan.variant},
            {"physical_source", measurement.plan.physical_source},
            {"status", profile_case.value("status", std::string("failed"))},
            {"error", profile_case.value("error", Json(nullptr))},
            {"attempt_count", profile_case.value("attempt_count", std::size_t{1})}};
}

Json guard_receipt(const GuardState& guards, const Json& arguments) {
    Json source_hashes = Json::object();
    for (const auto& [name, hash] : guards.source_hashes) source_hashes[name] = hash;
    return {{"passed", guards.mismatches.empty()},
            {"expected_frames", arguments.at("frames")},
            {"config_sha256", guards.config_hash.empty() ? Json(nullptr) : Json(guards.config_hash)},
            {"scene_sha256", guards.scene_hash.empty() ? Json(nullptr) : Json(guards.scene_hash)},
            {"source_identity_sha256", std::move(source_hashes)},
            {"aggregate_identity_count", guards.aggregate_identities.size()},
            {"program_identity_count", guards.program_identities.size()},
            {"runtime_state_restored", guards.runtime_state_restored},
            {"mismatches", guards.mismatches}};
}

} // namespace

Json visual_comparison_guards(const Json& result, bool require_heatmap) {
    Json mismatches = Json::array();
    const auto add_mismatch = [&mismatches](std::string code, std::string message) {
        mismatches.push_back({{"code", std::move(code)}, {"message", std::move(message)}});
    };

    const auto success = result.find("success");
    const bool runtime_success = success != result.end() && success->is_boolean() && success->get<bool>();
    if (!runtime_success) {
        add_mismatch("VISUAL_JOB_NOT_SUCCESSFUL",
            "The visual comparison job did not return a successful runtime completion receipt.");
    }

    const auto comparison = result.find("comparison");
    const bool comparison_receipt = comparison != result.end() && comparison->is_object() &&
        comparison->contains("passed") && comparison->at("passed").is_boolean() &&
        comparison->contains("verdict") && comparison->at("verdict").is_string() &&
        !comparison->at("verdict").get_ref<const std::string&>().empty();
    if (!comparison_receipt) {
        add_mismatch("THRESHOLD_VERDICT_MISSING",
            "The visual comparison did not return a typed threshold verdict.");
    }

    const auto frame_ids = result.find("frame_ids");
    const bool two_frames = frame_ids != result.end() && frame_ids->is_array() && frame_ids->size() == 2 &&
        frame_ids->at(0).is_number_integer() && frame_ids->at(1).is_number_integer() &&
        frame_ids->at(0) != frame_ids->at(1);
    if (!two_frames) {
        add_mismatch("TWO_DISTINCT_FRAME_RECEIPTS_REQUIRED",
            "The visual comparison must identify distinct baseline and candidate capture frames.");
    }

    bool metrics_artifact = false;
    const auto artifacts = result.find("artifacts");
    if (artifacts != result.end() && artifacts->is_array()) {
        metrics_artifact = std::ranges::any_of(*artifacts, [](const Json& artifact) {
            return artifact.is_object() && artifact.value("kind", std::string{}) == "ab_metrics" &&
                artifact.value("format", std::string{}) == "json" &&
                artifact.value("file_name", std::string{}) == "diff.json";
        });
    }
    if (!metrics_artifact) {
        add_mismatch("DIFF_METRICS_ARTIFACT_MISSING",
            "The visual comparison did not return its diff.json metrics artifact.");
    }

    bool heatmap_artifact = false;
    const auto groups = result.find("artifact_groups");
    if (groups != result.end() && groups->is_array()) {
        for (const auto& group : *groups) {
            if (!group.is_object() || group.value("name", std::string{}) != "diff-heatmap") continue;
            const auto group_artifacts = group.find("artifacts");
            if (group_artifacts == group.end() || !group_artifacts->is_array()) continue;
            heatmap_artifact = std::ranges::any_of(*group_artifacts, [](const Json& artifact) {
                if (!artifact.is_object() || artifact.value("kind", std::string{}) != "heatmap" ||
                    artifact.value("format", std::string{}) != "png") return false;
                const auto file_name = artifact.value("file_name", std::string{});
                return file_name == "diff-heatmap.png" ||
                    (file_name.starts_with("diff-heatmap.") && file_name.ends_with(".png"));
            });
            if (heatmap_artifact) break;
        }
    }
    if (require_heatmap && !heatmap_artifact) {
        add_mismatch("DIFF_HEATMAP_ARTIFACT_MISSING",
            "The visual comparison did not return its PNG difference heatmap artifact.");
    }

    std::vector<const Json*> loads;
    const auto action_results = result.find("action_results");
    if (action_results != result.end() && action_results->is_array()) {
        for (const auto& action : *action_results) {
            if (action.is_object() && action.value("kind", std::string{}) == "load_shader") {
                loads.push_back(&action);
            }
        }
    }
    bool load_receipts = loads.size() == 2;
    if (!load_receipts) {
        add_mismatch("TWO_SHADER_LOAD_RECEIPTS_REQUIRED",
            "The visual comparison must return exactly two shader load receipts.");
    }

    Json config_hashes = Json::array();
    Json scene_hashes = Json::array();
    Json source_hashes = Json::array();
    std::array<std::string, 2> config_values;
    std::array<std::string, 2> scene_values;
    std::array<std::string, 2> source_values;
    if (loads.size() == 2) {
        for (std::size_t index = 0; index < loads.size(); ++index) {
            const auto result_payload = loads[index]->find("result");
            const bool load_success = result_payload != loads[index]->end() && result_payload->is_object() &&
                result_payload->contains("success") && result_payload->at("success").is_boolean() &&
                result_payload->at("success").get<bool>();
            if (!load_success) {
                load_receipts = false;
                add_mismatch("SHADER_LOAD_RECEIPT_FAILED",
                    "A baseline or candidate shader load receipt was unsuccessful.");
            }
            config_values[index] = text_at(*loads[index], {"result", "provenance", "shader", "config_sha256"});
            scene_values[index] = text_at(*loads[index], {"result", "provenance", "scene", "context_sha256"});
            source_values[index] = text_at(*loads[index], {"result", "provenance", "source", "identity_sha256"});
            config_hashes.push_back(config_values[index].empty() ? Json(nullptr) : Json(config_values[index]));
            scene_hashes.push_back(scene_values[index].empty() ? Json(nullptr) : Json(scene_values[index]));
            source_hashes.push_back(source_values[index].empty() ? Json(nullptr) : Json(source_values[index]));
            if (config_values[index].empty()) {
                load_receipts = false;
                add_mismatch("CONFIG_HASH_MISSING",
                    "A shader load receipt omitted its effective config hash.");
            }
            if (scene_values[index].empty()) {
                load_receipts = false;
                add_mismatch("SCENE_HASH_MISSING",
                    "A shader load receipt omitted its effective scene-context hash.");
            }
            if (source_values[index].empty()) {
                load_receipts = false;
                add_mismatch("SOURCE_HASH_MISSING",
                    "A shader load receipt omitted its prepared source-identity hash.");
            }
        }
    }
    const bool config_match = loads.size() == 2 && !config_values[0].empty() &&
        config_values[0] == config_values[1];
    const bool scene_match = loads.size() == 2 && !scene_values[0].empty() &&
        scene_values[0] == scene_values[1];
    if (loads.size() == 2 && !config_values[0].empty() && !config_values[1].empty() && !config_match) {
        add_mismatch("VISUAL_CONFIG_HASH_MISMATCH",
            "Baseline and candidate captures used different effective shader configurations.");
    }
    if (loads.size() == 2 && !scene_values[0].empty() && !scene_values[1].empty() && !scene_match) {
        add_mismatch("VISUAL_SCENE_HASH_MISMATCH",
            "Baseline and candidate captures used different effective scene contexts.");
    }

    return {{"passed", mismatches.empty()},
            {"runtime_success", runtime_success},
            {"comparison_receipt", comparison_receipt},
            {"two_distinct_frames", two_frames},
            {"diff_metrics_artifact", metrics_artifact},
            {"diff_heatmap_required", require_heatmap},
            {"diff_heatmap_artifact", heatmap_artifact},
            {"two_successful_load_receipts", load_receipts},
            {"config_hash_match", config_match},
            {"scene_hash_match", scene_match},
            {"config_hashes", std::move(config_hashes)},
            {"scene_hashes", std::move(scene_hashes)},
            {"source_hashes", std::move(source_hashes)},
            {"mismatches", std::move(mismatches)}};
}

ToolOutcome run_paired_benchmark(const Json& arguments, std::string_view workflow_id,
    std::size_t default_warmup_frames, const PairedProfileExecutor& execute_profile,
    const PairedVisualExecutor& execute_visual) {
    const auto measurements_plan = plan(arguments);
    std::vector<Measurement> measurements;
    measurements.reserve(measurements_plan.size());
    Json artifacts = Json::array();
    bool halted_without_restore = false;
    for (const auto& item : measurements_plan) {
        const auto nested_arguments = profile_arguments(arguments, item, workflow_id, default_warmup_frames);
        auto outcome = execute_profile(nested_arguments);
        Measurement measurement{.plan = item};
        if (const auto* profile = std::get_if<Json>(&outcome)) {
            measurement.profile = *profile;
            measurement.profile_case = first_case(*profile, item, arguments, default_warmup_frames);
            const auto profile_artifacts = profile->value("artifacts", Json::array());
            for (const auto& artifact : profile_artifacts) {
                auto attributed = artifact;
                attributed["case_id"] = item.case_id;
                artifacts.push_back(std::move(attributed));
            }
        } else {
            const auto error = failure_json(std::get<ToolFailure>(outcome));
            measurement.profile = {{"success", false}, {"status", "completed_with_failures"},
                {"cases", Json::array({failed_case(item, arguments, error, default_warmup_frames)})}};
            measurement.profile_case = measurement.profile.at("cases").front();
        }
        measurement.metrics = metric_values(
            measurement.profile_case, arguments.value("statistic", std::string("avg")));
        measurements.push_back(std::move(measurement));
        if (!restored(measurements.back().profile_case)) {
            halted_without_restore = true;
            break;
        }
    }

    GuardState guards;
    validate_guards(measurements, arguments, guards);
    if (halted_without_restore) {
        guards.mismatches.push_back({{"code", "WORKFLOW_HALTED_BEFORE_RESTORATION"},
            {"field", "measurements"}, {"expected", measurements_plan.size()},
            {"actual", measurements.size()}});
    }
    const bool valid = guards.mismatches.empty();
    Json comparison_rounds = Json::array();
    Json control_rounds = Json::array();
    Json table = Json::array();
    if (valid) {
        comparison_rounds = round_samples(measurements, guards, "comparison",
            arguments.value("rounds", std::size_t{3}), "baseline", "candidate");
        control_rounds = round_samples(measurements, guards, "control",
            arguments.value("control_rounds", arguments.value("rounds", std::size_t{3})), "a", "b");
        table = comparison_table(guards, comparison_rounds, control_rounds);
    }

    Json executions = Json::array();
    Json profiles = Json::array();
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    for (const auto& measurement : measurements) {
        executions.push_back(compact_execution(measurement));
        const auto measurement_status = measurement.profile_case.value("status", std::string{});
        if (measurement_status == "passed") ++passed;
        else if (measurement_status == "failed") ++failed;
        else ++incomplete;
        if (arguments.value("result_detail", std::string("metrics")) == "full") {
            profiles.push_back(measurement.profile);
        }
    }
    const auto performance_verdict = valid ? overall_verdict(table) : "inconclusive";
    Json visual{{"requested", false}, {"success", true}, {"status", "not_requested"},
                {"verdict", "not_requested"}, {"comparison", nullptr}, {"artifacts", Json::array()}};
    if (arguments.contains("visual")) {
        if (!execute_visual) {
            visual = {{"requested", true}, {"success", false}, {"status", "failed"},
                      {"verdict", "inconclusive"},
                      {"error", {{"success", false}, {"error_code", "VISUAL_EXECUTOR_UNAVAILABLE"},
                          {"message", "Visual comparison execution is unavailable."}, {"retryable", false}}},
                      {"comparison", nullptr}, {"artifacts", Json::array()}};
        } else if (halted_without_restore) {
            visual = {{"requested", true}, {"success", false}, {"status", "skipped"},
                      {"verdict", "inconclusive"},
                      {"error", {{"success", false}, {"error_code", "RUNTIME_STATE_NOT_RESTORED"},
                          {"message", "Visual comparison was skipped because benchmark state was not restored."},
                          {"retryable", true}}},
                      {"comparison", nullptr}, {"artifacts", Json::array()}};
        } else {
            visual = visual_receipt(execute_visual(visual_arguments(arguments, default_warmup_frames)));
            for (const auto& artifact : visual.at("artifacts")) {
                auto attributed = artifact;
                attributed["benchmark_phase"] = "visual";
                artifacts.push_back(std::move(attributed));
            }
        }
    }
    const bool performance_success = failed == 0 && incomplete == 0 && valid;
    const bool visual_success = visual.at("success").get<bool>();
    const bool visual_invalid = visual.value("status", std::string{}) == "invalid";
    const auto status = halted_without_restore || incomplete != 0 ? "incomplete" :
        (failed != 0 ? "completed_with_failures" :
            (!valid || visual_invalid ? "invalid_comparison" :
                (!visual_success ? "completed_with_failures" : "completed")));
    const auto combined_verdict = !valid || visual.at("verdict") == "inconclusive"
        ? std::string("inconclusive")
        : (visual.at("verdict") == "failed" ? std::string("failed") : performance_verdict);
    Json result{{"success", performance_success && visual_success},
                {"kind", "benchmark_ab"},
                {"status", status},
                {"verdict", combined_verdict},
                {"performance_verdict", performance_verdict},
                {"visual_verdict", visual.at("verdict")},
                {"visual", std::move(visual)},
                {"result_detail", arguments.value("result_detail", std::string("metrics"))},
                {"gpu_timing_unit", "ns"},
                {"statistic", arguments.value("statistic", std::string("avg"))},
                {"metric_filter", arguments.value("metric_filter", Json(nullptr))},
                {"order", arguments.value("order", std::string("abba"))},
                {"random_seed", arguments.value("random_seed", std::uint32_t{0})},
                {"round_count", arguments.value("rounds", std::size_t{3})},
                {"control_round_count", arguments.value(
                    "control_rounds", arguments.value("rounds", std::size_t{3}))},
                {"requested_measurements", measurements_plan.size()},
                {"completed_measurements", passed + failed},
                {"passed_measurements", passed},
                {"failed_measurements", failed},
                {"incomplete_measurements", incomplete},
                {"remaining_measurements", measurements_plan.size() - measurements.size()},
                {"same_commit_control_source", "baseline"},
                {"guards", guard_receipt(guards, arguments)},
                {"executions", std::move(executions)},
                {"round_samples", std::move(comparison_rounds)},
                {"control_round_samples", std::move(control_rounds)},
                {"comparison_table", std::move(table)},
                {"artifacts", std::move(artifacts)}};
    if (!profiles.empty()) result["profiles"] = std::move(profiles);
    return result;
}

std::vector<PairedBenchmarkStep> paired_benchmark_plan(const Json& arguments) {
	std::vector<PairedBenchmarkStep> result;
	for (const auto& item : plan(arguments)) {
		result.push_back({item.phase, item.round, item.slot, item.variant,
			item.physical_source, item.case_id});
	}
	return result;
}

Json paired_benchmark_profile_arguments(const Json& arguments,
	const PairedBenchmarkStep& step, std::string_view workflow_id,
	const std::size_t default_warmup_frames) {
	return profile_arguments(arguments,
		{step.phase, step.round, step.slot, step.variant, step.physical_source, step.case_id},
		workflow_id, default_warmup_frames);
}

Json paired_benchmark_visual_arguments(
	const Json& arguments, const std::size_t default_warmup_frames) {
	return visual_arguments(arguments, default_warmup_frames);
}

} // namespace vibris::mcp
