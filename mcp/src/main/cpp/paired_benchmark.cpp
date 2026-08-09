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
    return result;
}

Json failure_json(const ToolFailure& failure) {
    return {{"success", false}, {"error_code", failure.code}, {"message", failure.message},
            {"retryable", failure.retryable}, {"details", failure.details}};
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

ToolOutcome run_paired_benchmark(const Json& arguments, std::string_view workflow_id,
    std::size_t default_warmup_frames, const PairedProfileExecutor& execute_profile) {
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
    const auto status = halted_without_restore || incomplete != 0 ? "incomplete" :
        (failed != 0 ? "completed_with_failures" : (valid ? "completed" : "invalid_comparison"));
    Json result{{"success", failed == 0 && incomplete == 0 && valid},
                {"kind", "benchmark_ab"},
                {"status", status},
                {"verdict", valid ? overall_verdict(table) : "inconclusive"},
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

} // namespace vibris::mcp