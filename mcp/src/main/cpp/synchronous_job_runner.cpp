#include "synchronous_job_runner.hpp"

#include "config_document.hpp"
#include "job_protocol.hpp"
#include "paired_benchmark.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v2;

struct CompletionState final {
    std::mutex mutex;
    std::condition_variable ready;
    grpc::Status status;
    std::optional<proto::ServerMessage> terminal;
    bool done = false;
};

ToolFailure transport_failure(const grpc::Status& status) {
    if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
        return {"SERVER_RESTARTED", "The accepted Vibris job was lost after reconnect.", true};
    }
    auto message = status.error_message();
    if (message.empty()) message = "The local Vibris server disconnected before the job completed.";
    if (message.size() > 512) message.resize(512);
    return {"SERVER_OFFLINE", std::move(message), true};
}

ToolFailure request_failure(ToolFailure failure, std::string_view request_id, const bool request_accepted) {
    failure.details["request_id"] = request_id;
    failure.details["request_accepted"] = request_accepted;
    if (request_accepted) failure.details["resume_required"] = true;
    return failure;
}

std::string progress_stage(const proto::JobStage stage) {
    switch (stage) {
        case proto::JOB_STAGE_VALIDATING:
        case proto::JOB_STAGE_QUEUED:
        case proto::JOB_STAGE_ACTIVATING_SOURCE:
        case proto::JOB_STAGE_LOADING_WORLD:
        case proto::JOB_STAGE_APPLYING_CONTEXT:
        case proto::JOB_STAGE_COMPILING:
            return "loading";
        case proto::JOB_STAGE_RESETTING_TEMPORAL_STATE:
        case proto::JOB_STAGE_WARMING_UP:
            return "warming";
        case proto::JOB_STAGE_SAMPLING:
        case proto::JOB_STAGE_CAPTURING:
            return "sampling";
        case proto::JOB_STAGE_WRITING_ARTIFACTS:
        case proto::JOB_STAGE_FINALIZING:
            return "checkpointing";
        case proto::JOB_STAGE_COMPARING:
            return "sampling";
        case proto::JOB_STAGE_UNSPECIFIED:
            return "loading";
    }
    return "loading";
}

void report_progress(const SynchronousJobProgressSink& sink, std::string request_id,
    std::string stage, const bool accepted) {
    if (sink) sink({std::move(request_id), std::move(stage), accepted});
}

bool failed_action(const Json& action) {
    const auto& result = action.at("result");
    return result.is_object() && result.contains("success") && result.at("success").is_boolean() &&
        !result.at("success").get<bool>();
}

bool has_gpu_samples(const Json& result) {
    if (!result.is_object()) return false;
    const auto timings = result.find("gpuTimings");
    if (timings != result.end() && timings->is_object() && !timings->empty()) return true;
    const auto programs = result.find("gpuProgramTimings");
    return programs != result.end() && programs->is_array() &&
        std::any_of(programs->begin(), programs->end(), [](const Json& program) {
            if (!program.is_object()) return false;
            const auto statistics = program.find("statistics");
            return statistics != program.end() && statistics->is_object() && !statistics->empty();
        });
}

bool wildcard_match(std::string_view pattern, std::string_view value) {
    std::size_t pattern_index = 0;
    std::size_t value_index = 0;
    std::size_t star = std::string_view::npos;
    std::size_t retry = 0;
    while (value_index < value.size()) {
        if (pattern_index < pattern.size() && pattern[pattern_index] == value[value_index]) {
            ++pattern_index;
            ++value_index;
        } else if (pattern_index < pattern.size() && pattern[pattern_index] == '*') {
            star = pattern_index++;
            retry = value_index;
        } else if (star != std::string_view::npos) {
            pattern_index = star + 1;
            value_index = ++retry;
        } else {
            return false;
        }
    }
    while (pattern_index < pattern.size() && pattern[pattern_index] == '*') ++pattern_index;
    return pattern_index == pattern.size();
}

bool selected_metric(const Json& arguments, std::string_view name) {
    const auto filter = arguments.find("metric_filter");
    if (filter == arguments.end()) return true;
    for (const auto& pattern : *filter) {
        if (wildcard_match(pattern.get_ref<const std::string&>(), name)) return true;
    }
    return false;
}

bool selected_program_timing(const Json& arguments, const Json& timing) {
    const auto filter = arguments.find("metric_filter");
    if (filter == arguments.end()) return true;
    constexpr std::array fields{
        "metric", "program", "stage", "source", "dispatch", "framework_pass", "compatibility_metric",
    };
    for (const auto& pattern_value : *filter) {
        const auto& pattern = pattern_value.get_ref<const std::string&>();
        for (const auto* field : fields) {
            const auto found = timing.find(field);
            if (found != timing.end() && found->is_string() &&
                wildcard_match(pattern, found->get_ref<const std::string&>())) {
                return true;
            }
        }
        const auto defines = timing.find("defines");
        if (defines == timing.end() || !defines->is_object()) continue;
        for (const auto& [name, value] : defines->items()) {
            if (wildcard_match(pattern, name) ||
                (value.is_string() &&
                    (wildcard_match(pattern, value.get_ref<const std::string&>()) ||
                        wildcard_match(pattern, name + "=" + value.get_ref<const std::string&>())))) {
                return true;
            }
        }
    }
    return false;
}

bool selected_statistic(const Json& arguments, std::string_view name) {
    const auto filter = arguments.find("statistics");
    if (filter == arguments.end()) return true;
    return std::ranges::any_of(*filter, [name](const Json& statistic) {
        return statistic.get_ref<const std::string&>() == name;
    });
}

Json filtered_statistics(const Json& statistics, const Json& arguments) {
    Json result = Json::object();
    for (const auto& [statistic, value] : statistics.items()) {
        if (!selected_statistic(arguments, statistic)) continue;
        result[statistic] = value;
        if (!value.is_number()) continue;
        const auto nanoseconds = value.get<double>();
        for (const auto& unit : arguments.value("converted_units", Json::array())) {
            const auto& name = unit.get_ref<const std::string&>();
            if (name == "us") result[statistic + "_us"] = nanoseconds / 1'000.0;
            if (name == "ms") result[statistic + "_ms"] = nanoseconds / 1'000'000.0;
        }
    }
    return result;
}

Json filtered_metrics(const Json& metrics, const Json& arguments) {
    Json result = metrics;
    Json filtered_timings = Json::object();
    const auto timing_values = metrics.value("gpuTimings", Json::object());
    for (const auto& [metric_name, statistics] : timing_values.items()) {
        if (!selected_metric(arguments, metric_name)) continue;
        filtered_timings[metric_name] = statistics.is_object()
            ? filtered_statistics(statistics, arguments)
            : statistics;
    }
    result["gpuTimings"] = std::move(filtered_timings);

    Json filtered_scopes = Json::array();
    const auto scope_values = metrics.value("gpuTimingScopes", Json::array());
    for (const auto& scope : scope_values) {
        if (scope.is_object() && selected_metric(arguments, scope.value("metric", std::string{}))) {
            filtered_scopes.push_back(scope);
        }
    }
    result["gpuTimingScopes"] = std::move(filtered_scopes);

    Json filtered_programs = Json::array();
    const auto program_values = metrics.value("gpuProgramTimings", Json::array());
    for (const auto& timing : program_values) {
        if (!timing.is_object() || !selected_program_timing(arguments, timing)) continue;
        auto filtered = timing;
        if (const auto statistics = timing.find("statistics");
            statistics != timing.end() && statistics->is_object()) {
            filtered["statistics"] = filtered_statistics(*statistics, arguments);
        }
        filtered_programs.push_back(std::move(filtered));
    }
    result["gpuProgramTimings"] = std::move(filtered_programs);
    return result;
}

Json no_gpu_samples_error(std::string_view case_id, std::string_view reason) {
    return {{"success", false},
            {"error_code", "NO_GPU_SAMPLES"},
            {"message", "GPU metrics did not return aggregate or program timing samples."},
            {"retryable", true},
            {"details", {{"case_id", case_id}, {"reason", reason}}}};
}

Json incomplete_provenance_error(std::string_view case_id) {
    return {{"success", false},
            {"error_code", "INCOMPLETE_PROVENANCE"},
            {"message", "Benchmark provenance did not prove the complete measured state."},
            {"retryable", false},
            {"details", {{"case_id", case_id}}}};
}

Json incomplete_barriers_error(std::string_view case_id) {
    return {{"success", false},
            {"error_code", "BENCHMARK_BARRIER_FAILED"},
            {"message", "The benchmark case did not prove isolation and final state restoration."},
            {"retryable", false},
            {"details", {{"case_id", case_id}}}};
}

struct ProfileCounts final {
    std::size_t requested = 0;
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    std::size_t with_metrics = 0;
};

Json case_artifacts(const Json& job, std::string_view case_id, bool matrix) {
    if (!matrix) return job.at("artifacts");
    Json result = Json::array();
    for (const auto& artifact : job.at("artifacts")) {
        if (artifact.at("file_name").get_ref<const std::string&>().starts_with(
                std::string(case_id) + "--")) {
            result.push_back(artifact);
        }
    }
    return result;
}

Json profile_artifacts(const Json& job) {
    Json result = Json::array();
    for (const auto& artifact : job.at("artifacts")) {
        if (artifact.at("kind") == "profile_result") result.push_back(artifact);
    }
    return result;
}

void append_profile_case(const Json& job, Json& cases, ProfileCounts& counts, const Json& arguments,
    std::size_t warmup_frames, std::string case_id, std::string source_id, std::string config_id,
    std::size_t first_action, std::size_t last_action, bool matrix, bool explicit_identity,
    std::string_view detail) {
    const bool full = detail == "full";
    const bool include_metrics = detail != "summary";
    Json action_results = Json::array();
    Json metrics = nullptr;
    Json error = nullptr;
    Json provenance = nullptr;
    bool metrics_seen = false;
    for (const auto& action : job.at("action_results")) {
        const auto index = action.at("action_index").get<std::size_t>();
        const auto action_case = action.value("case_id", std::string{});
        if (explicit_identity ? action_case != case_id :
            (!action_case.empty() ? action_case != case_id : index < first_action || index >= last_action)) {
            continue;
        }
        if (failed_action(action) && error.is_null()) error = action.at("result");
        if (action.at("kind") == "load_shader" && action.at("result").is_object()) {
            const auto found = action.at("result").find("provenance");
            if (found != action.at("result").end()) provenance = *found;
        }
        if (action.at("kind") == "get_gpu_metrics") {
            metrics_seen = true;
            if (has_gpu_samples(action.at("result"))) metrics = action.at("result");
            continue;
        }
        if (!full) continue;
        auto mapped = action;
        mapped["action_index"] = index - first_action;
        action_results.push_back(std::move(mapped));
    }

    Json barriers = Json::array();
    bool restored = !explicit_identity;
    for (const auto& receipt : job.value("benchmark_barriers", Json::array())) {
        if (receipt.value("case_id", std::string{}) != case_id) continue;
        if (receipt.value("stage", std::string{}) == "state_restored") restored = true;
        barriers.push_back(receipt);
    }

    ++counts.requested;
    const bool has_metrics = !metrics.is_null();
    const bool has_provenance = provenance.is_object() && provenance.value("complete", false);
    if (has_metrics) ++counts.with_metrics;
    const bool failed = !error.is_null();
    const bool incomplete = !failed && (!has_metrics || !has_provenance || !restored);
    const char* status = "passed";
    if (failed) {
        ++counts.failed;
        status = "failed";
    } else if (incomplete) {
        ++counts.incomplete;
        status = "incomplete";
        error = !has_metrics ? no_gpu_samples_error(
            case_id, metrics_seen ? "empty_gpu_timings" : "missing_gpu_metrics_action") :
            (!has_provenance ? incomplete_provenance_error(case_id) : incomplete_barriers_error(case_id));
    } else {
        ++counts.passed;
    }

    Json visible_metrics = include_metrics && has_metrics ? filtered_metrics(metrics, arguments) : Json(nullptr);
    Json item{{"case_id", std::move(case_id)},
              {"source_id", std::move(source_id)},
              {"config_id", std::move(config_id)},
              {"status", status},
              {"error", std::move(error)},
              {"frames", arguments.at("frames")},
              {"warmup_frames", warmup_frames},
              {"metrics", std::move(visible_metrics)},
              {"provenance", std::move(provenance)},
              {"barriers", std::move(barriers)}};
    if (full) {
        item["action_results"] = std::move(action_results);
        item["artifacts"] = case_artifacts(job, item.at("case_id").get<std::string>(), matrix);
    }
    cases.push_back(std::move(item));
}

void append_full_job_metadata(const Json& job, Json& result) {
    constexpr std::array fields{
        "diagnostics", "comparison", "timings", "frame_ids", "artifacts",
        "artifact_groups", "benchmark_barriers", "manifest_path", "recovered_from_artifact", "recovered_attempt",
        "recovered_previous_attempts",
    };
    for (const auto* field : fields) {
        const auto value = job.find(field);
        if (value != job.end()) result[field] = *value;
    }
}

Json profile_result(Json job, const Json& arguments, const JobContext& config, bool matrix) {
    const auto detail = arguments.value("result_detail", std::string("metrics"));
    const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
    const bool explicit_identity = arguments.contains("__vibris_workflow_id");
    Json cases = Json::array();
    ProfileCounts counts;
    if (matrix) {
        const std::size_t case_size = (warmup == 0 ? 1 : 2) + 1;
        std::size_t case_index = 0;
        for (const auto& source : arguments.at("matrix").at("sources")) {
            for (const auto& shader_config : arguments.at("matrix").at("configs")) {
                const auto source_id = source.get<std::string>();
                const auto config_id = shader_config.get<std::string>();
                const auto first = case_index * case_size;
                append_profile_case(job, cases, counts, arguments, warmup,
                    source_id + "--" + config_id, source_id, config_id, first, first + case_size,
                    true, explicit_identity, detail);
                ++case_index;
            }
        }
    } else {
        append_profile_case(job, cases, counts, arguments, warmup,
            arguments.value("__vibris_case_id", std::string("source--config")),
            arguments.value("__vibris_source_id", std::string("source")),
            arguments.value("__vibris_config_id", std::string("config")),
            0, std::numeric_limits<std::size_t>::max(), false, explicit_identity, detail);
    }

    Json result{
        {"success", counts.failed == 0 && counts.incomplete == 0},
        {"kind", matrix ? "profile_matrix" : "profile"},
        {"status", counts.incomplete != 0 ? "incomplete" :
            (counts.failed == 0 ? "completed" : "completed_with_failures")},
        {"result_detail", detail},
        {"gpu_timing_unit", "ns"},
        {"metric_filter", arguments.value("metric_filter", Json(nullptr))},
        {"statistics", arguments.value("statistics", Json(nullptr))},
        {"converted_units", arguments.value("converted_units", Json::array())},
        {"requested_cases", counts.requested},
        {"completed_cases", counts.passed + counts.failed},
        {"cases_with_metrics", counts.with_metrics},
        {"missing_cases", counts.requested - counts.with_metrics},
        {"failed_cases", counts.failed},
        {"retried_cases", 0},
        {"passed", counts.passed},
        {"failed", counts.failed},
        {"incomplete", counts.incomplete},
        {"cases", std::move(cases)},
        {"artifacts", profile_artifacts(job)},
    };
    for (const auto* field : {"recovered_from_artifact", "recovered_attempt", "recovered_previous_attempts"}) {
        if (const auto value = job.find(field); value != job.end()) result[field] = *value;
    }
    if (detail == "full") append_full_job_metadata(job, result);
    return result;
}

Json matrix_result(Json job, const Json& arguments) {
    const std::size_t case_size = arguments.at("actions").size() + 1;
    Json cases = Json::array();
    std::size_t case_index = 0;
    std::size_t failures = 0;
    for (const auto& source : arguments.at("matrix").at("sources")) {
        for (const auto& shader_config : arguments.at("matrix").at("configs")) {
            const auto source_id = source.get<std::string>();
            const auto config_id = shader_config.get<std::string>();
            const auto case_id = source_id + "--" + config_id;
            const auto first = case_index * case_size;
            const auto last = first + case_size;
            Json results = Json::array();
            Json error = nullptr;
            for (const auto& action : job.at("action_results")) {
                const auto index = action.at("action_index").get<std::size_t>();
                const auto action_case = action.value("case_id", std::string{});
                if (!action_case.empty() ? action_case != case_id : index < first || index >= last) continue;
                auto mapped = action;
                mapped["action_index"] = index - first;
                if (failed_action(action) && error.is_null()) error = action.at("result");
                results.push_back(std::move(mapped));
            }
            Json artifacts = case_artifacts(job, case_id, true);
            const bool failed = !error.is_null();
            if (failed) ++failures;
            cases.push_back({{"id", case_id},
                             {"source", source_id},
                             {"config", config_id},
                             {"status", failed ? "failed" : "passed"},
                             {"error", std::move(error)},
                             {"action_results", std::move(results)},
                             {"artifacts", std::move(artifacts)}});
            ++case_index;
        }
    }
    job["kind"] = "matrix";
    job["status"] = failures == 0 ? "completed" : "completed_with_failures";
    job["passed"] = case_index - failures;
    job["failed"] = failures;
    job["cases"] = std::move(cases);
    return job;
}

struct ProfileCaseSpec final {
    std::string case_id;
    std::string source_id;
    std::string config_id;
    Json source = nullptr;
    Json config = nullptr;
};

struct ProfileCaseState final {
    ProfileCaseSpec spec;
    Json value = nullptr;
    Json attempts = Json::array();
    std::size_t run_attempts = 0;
};

const Json& named_value(const Json& values, std::string_view id, std::string_view kind) {
    const auto found = std::ranges::find_if(values, [id](const Json& value) {
        return value.at("id").get_ref<const std::string&>() == id;
    });
    if (found == values.end()) throw std::invalid_argument(std::string(kind) + " ID is not declared");
    return *found;
}

std::vector<ProfileCaseState> profile_cases(const Json& arguments, bool matrix) {
    std::vector<ProfileCaseState> result;
    if (!matrix) {
        ProfileCaseSpec spec{
            arguments.value("__vibris_case_id", std::string("source--config")),
            arguments.value("__vibris_source_id", std::string("source")),
            arguments.value("__vibris_config_id", std::string("config")),
        };
        if (arguments.contains("source")) spec.source = arguments.at("source");
        if (arguments.contains("config")) spec.config = arguments.at("config");
        result.push_back({std::move(spec)});
        return result;
    }
    for (const auto& source_axis : arguments.at("matrix").at("sources")) {
        const auto source_id = source_axis.get<std::string>();
        auto source = named_value(arguments.at("sources"), source_id, "source");
        source.erase("id");
        for (const auto& config_axis : arguments.at("matrix").at("configs")) {
            const auto config_id = config_axis.get<std::string>();
            const auto& named_config = named_value(arguments.at("configs"), config_id, "config");
            result.push_back({ProfileCaseSpec{
                source_id + "--" + config_id,
                source_id,
                config_id,
                source,
                named_config.contains("values") ? named_config.at("values") : Json(nullptr),
            }});
        }
    }
    return result;
}

Json failure_error(const ToolFailure& failure) {
    return { {"success", false},
             {"error_code", failure.code},
             {"message", failure.message},
             {"retryable", failure.retryable},
             {"details", failure.details} };
}

bool retryable_error(const Json& error) {
    if (!error.is_object()) return false;
    const auto code = error.value("error_code", std::string{});
    constexpr std::array retryable_codes{
        "NO_GPU_SAMPLES", "SERVER_OFFLINE", "SERVER_RESTARTED", "SERVER_NOT_READY",
        "QUEUE_FULL", "QUEUE_TIMEOUT", "EXECUTION_TIMEOUT", "WORLD_LOAD_FAILED",
        "SOURCE_ACTIVATION_FAILED", "INTERNAL_ERROR", "CAPTURE_FAILED",
    };
    if (std::ranges::find(retryable_codes, code) != retryable_codes.end()) return true;
    return error.value("retryable", false);
}

bool resume_required(const Json& error) {
    const auto details = error.find("details");
    return details != error.end() && details->is_object() && details->value("resume_required", false);
}

Json failed_profile_case(
    const ProfileCaseSpec& spec, const Json& arguments, std::size_t default_warmup, Json error) {
    return {{"case_id", spec.case_id},
            {"source_id", spec.source_id},
            {"config_id", spec.config_id},
            {"status", "failed"},
            {"error", std::move(error)},
            {"frames", arguments.at("frames")},
            {"warmup_frames", arguments.value("warmup_frames", default_warmup)},
            {"metrics", nullptr},
            {"provenance", nullptr}};
}

const Json* find_profile_case(const Json& result, std::string_view case_id) {
    const auto cases = result.find("cases");
    if (cases == result.end() || !cases->is_array()) return nullptr;
    const auto found = std::ranges::find_if(*cases, [case_id](const Json& value) {
        return value.at("case_id").get_ref<const std::string&>() == case_id;
    });
    return found == cases->end() ? nullptr : &*found;
}

Json missing_profile_case(const ProfileCaseSpec& spec, const Json& arguments, std::size_t default_warmup) {
    auto result = failed_profile_case(spec, arguments, default_warmup,
        no_gpu_samples_error(spec.case_id, "missing_case_result"));
    result["status"] = "incomplete";
    return result;
}

Json collect_artifacts(const Json& source, std::size_t attempt, const Json& case_ids, Json& artifacts) {
    Json ids = Json::array();
    const auto found = source.find("artifacts");
    if (found == source.end() || !found->is_array()) return ids;
    for (const auto& value : *found) {
        auto artifact = value;
        artifact["attempt"] = attempt;
        artifact["case_ids"] = case_ids;
        const auto id = artifact.value("artifact_id", artifact.value("file_name", std::string{}));
        if (!id.empty()) ids.push_back(id);
        artifacts.push_back(std::move(artifact));
    }
    return ids;
}

Json attempt_record(std::size_t attempt, const Json& profile_case, Json artifact_ids) {
    const auto& error = profile_case.at("error");
    return {{"attempt", attempt},
            {"status", profile_case.at("status")},
            {"retryable", retryable_error(error)},
            {"error", error},
            {"artifact_ids", std::move(artifact_ids)}};
}

Json job_attempt_record(std::size_t attempt, const Json& case_ids, std::string status, Json error,
    Json artifact_ids, const Json* terminal) {
    Json result{{"attempt", attempt},
                {"case_ids", case_ids},
                {"status", std::move(status)},
                {"error", std::move(error)},
                {"artifact_ids", std::move(artifact_ids)}};
    if (terminal != nullptr && terminal->contains("timings")) result["timings"] = terminal->at("timings");
    return result;
}

Json retry_arguments(const Json& arguments, const ProfileCaseState& state, std::size_t attempt) {
    Json result{{"recipe", "profile"}, {"frames", arguments.at("frames")}};
    constexpr std::array copied_fields{
        "warmup_frames", "result_detail", "metric_filter", "statistics", "converted_units", "result_csv",
    };
    for (const auto* field : copied_fields) {
        if (arguments.contains(field)) result[field] = arguments.at(field);
    }
    if (arguments.contains("__vibris_preset")) result["__vibris_preset"] = arguments.at("__vibris_preset");
    if (arguments.contains("__vibris_workflow_id")) {
        result["__vibris_workflow_id"] = arguments.at("__vibris_workflow_id");
    }
    if (!state.spec.source.is_null()) result["source"] = state.spec.source;
    if (!state.spec.config.is_null()) result["config"] = state.spec.config;
    result["__vibris_case_id"] = state.spec.case_id;
    result["__vibris_source_id"] = state.spec.source_id;
    result["__vibris_config_id"] = state.spec.config_id;
    result["__vibris_result_kind"] = arguments.value("__vibris_result_kind", arguments.at("recipe"));
    result["__vibris_attempt"] = attempt;
    result["__vibris_previous_attempts"] = state.attempts;
    return result;
}

bool case_has_metrics(const Json& profile_case) {
    if (profile_case.at("status") == "passed") return true;
    const auto metrics = profile_case.find("metrics");
    return metrics != profile_case.end() && has_gpu_samples(*metrics);
}

using ProfileAttempt = std::function<ToolOutcome(const Json&, bool)>;

Json retry_profile(
    const Json& arguments, bool matrix, std::size_t default_warmup, const ProfileAttempt& submit) {
    auto states = profile_cases(arguments, matrix);
    if (!matrix && arguments.contains("__vibris_previous_attempts")) {
        states.front().attempts = arguments.at("__vibris_previous_attempts");
    }
    const auto max_retries = arguments.value("max_retries", std::size_t{2});
    Json artifacts = Json::array();
    Json job_attempts = Json::array();
    Json aggregate = Json::object();
    bool aggregate_initialized = false;

    Json initial_arguments = arguments;
    const auto initial_attempt = states.front().attempts.size() + 1;
    initial_arguments["__vibris_attempt"] = initial_attempt;
    const auto initial_outcome = submit(initial_arguments, matrix);
    Json all_case_ids = Json::array();
    for (const auto& state : states) all_case_ids.push_back(state.spec.case_id);
    if (const auto* result = std::get_if<Json>(&initial_outcome)) {
        aggregate = *result;
        aggregate_initialized = true;
        if (!matrix && result->contains("recovered_previous_attempts") &&
            states.front().attempts.size() < result->at("recovered_previous_attempts").size()) {
            states.front().attempts = result->at("recovered_previous_attempts");
        }
        const auto actual_attempt = result->value("recovered_attempt", initial_attempt);
        aggregate.erase("recovered_attempt");
        aggregate.erase("recovered_previous_attempts");
        const auto artifact_ids = collect_artifacts(*result, actual_attempt, all_case_ids, artifacts);
        job_attempts.push_back(job_attempt_record(
            actual_attempt, all_case_ids, result->at("status").get<std::string>(), nullptr, artifact_ids, result));
        for (auto& state : states) {
            const auto* profile_case = find_profile_case(*result, state.spec.case_id);
            state.value = profile_case == nullptr ?
                missing_profile_case(state.spec, arguments, default_warmup) : *profile_case;
            state.attempts.push_back(attempt_record(actual_attempt, state.value, artifact_ids));
            state.run_attempts = 1;
        }
    } else {
        const auto& failure = std::get<ToolFailure>(initial_outcome);
        const auto error = failure_error(failure);
        const auto artifact_ids = collect_artifacts(failure.details, initial_attempt, all_case_ids, artifacts);
        job_attempts.push_back(job_attempt_record(
            initial_attempt, all_case_ids, "failed", error, artifact_ids, nullptr));
        for (auto& state : states) {
            state.value = failed_profile_case(state.spec, arguments, default_warmup, error);
            state.attempts.push_back(attempt_record(initial_attempt, state.value, artifact_ids));
            state.run_attempts = 1;
        }
    }

    for (auto& state : states) {
        while (retryable_error(state.value.at("error")) && !resume_required(state.value.at("error")) &&
            state.run_attempts <= max_retries) {
            const auto attempt = state.attempts.size() + 1;
            const auto outcome = submit(retry_arguments(arguments, state, attempt), false);
            const Json case_ids = Json::array({state.spec.case_id});
            if (const auto* result = std::get_if<Json>(&outcome)) {
                if (!aggregate_initialized) {
                    aggregate = *result;
                    aggregate_initialized = true;
                }
                const auto artifact_ids = collect_artifacts(*result, attempt, case_ids, artifacts);
                job_attempts.push_back(job_attempt_record(
                    attempt, case_ids, result->at("status").get<std::string>(), nullptr, artifact_ids, result));
                const auto* profile_case = find_profile_case(*result, state.spec.case_id);
                state.value = profile_case == nullptr ?
                    missing_profile_case(state.spec, arguments, default_warmup) : *profile_case;
                state.attempts.push_back(attempt_record(attempt, state.value, artifact_ids));
                ++state.run_attempts;
            } else {
                const auto& failure = std::get<ToolFailure>(outcome);
                const auto error = failure_error(failure);
                const auto artifact_ids = collect_artifacts(failure.details, attempt, case_ids, artifacts);
                job_attempts.push_back(
                    job_attempt_record(attempt, case_ids, "failed", error, artifact_ids, nullptr));
                state.value = failed_profile_case(state.spec, arguments, default_warmup, error);
                state.attempts.push_back(attempt_record(attempt, state.value, artifact_ids));
                ++state.run_attempts;
            }
        }
    }

    if (!aggregate_initialized) {
        aggregate = {{"result_detail", arguments.value("result_detail", std::string("metrics"))},
                     {"gpu_timing_unit", "ns"},
                     {"metric_filter", arguments.value("metric_filter", Json(nullptr))},
                     {"statistics", arguments.value("statistics", Json(nullptr))},
                     {"converted_units", arguments.value("converted_units", Json::array())}};
    }

    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    std::size_t with_metrics = 0;
    std::size_t retried = 0;
    std::size_t total_attempts = 0;
    Json cases = Json::array();
    for (auto& state : states) {
        const auto status = state.value.at("status").get<std::string>();
        if (status == "passed") ++passed;
        else if (status == "failed") ++failed;
        else ++incomplete;
        if (case_has_metrics(state.value)) ++with_metrics;
        if (state.attempts.size() > 1) ++retried;
        total_attempts += state.attempts.size();
        state.value["attempt_count"] = state.attempts.size();
        state.value["retry_exhausted"] = retryable_error(state.value.at("error")) &&
            state.run_attempts == max_retries + 1;
        state.value["attempts"] = std::move(state.attempts);
        cases.push_back(std::move(state.value));
    }
    aggregate["success"] = passed == states.size();
    aggregate["kind"] = matrix ? "profile_matrix" : "profile";
    aggregate["status"] = incomplete != 0 ? "incomplete" : (failed == 0 ? "completed" : "completed_with_failures");
    aggregate["requested_cases"] = states.size();
    aggregate["completed_cases"] = passed + failed;
    aggregate["cases_with_metrics"] = with_metrics;
    aggregate["missing_cases"] = states.size() - with_metrics;
    aggregate["failed_cases"] = failed;
    aggregate["retried_cases"] = retried;
    aggregate["total_attempts"] = total_attempts;
    aggregate["max_retries"] = max_retries;
    aggregate["passed"] = passed;
    aggregate["failed"] = failed;
    aggregate["incomplete"] = incomplete;
    aggregate["cases"] = std::move(cases);
    aggregate["artifacts"] = std::move(artifacts);
    aggregate["job_attempts"] = std::move(job_attempts);
    return aggregate;
}

}

SynchronousJobRunner::SynchronousJobRunner(
    GrpcClient& client, SourceHandler& sources, const JobContext& config,
    const std::chrono::milliseconds maximum_wait)
    : client_(client), sources_(sources), config_(config), maximum_wait_(maximum_wait) {
    if (maximum_wait_.count() < 0) throw std::invalid_argument("maximum wait must not be negative");
}

ToolOutcome SynchronousJobRunner::submit_once(std::string_view tool_name, const Json& arguments,
    const proto::ServerHello& server, const proto::SceneContext& context,
    const SynchronousJobControl& control) {
    const auto request_id = detail::generate_uuid();
    report_progress(control.progress, request_id, "loading", false);
    sources_.prepare(tool_name, arguments, server);
    const auto references = sources_.bind_latest(request_id);
    try {
        auto request = JobProtocol::request(tool_name, arguments, config_, context, references, request_id);
        const auto server_timeout = std::chrono::milliseconds(request.submit_job().job().timeouts().total_timeout_ms());
        const auto maximum_wait = maximum_wait_.count() == 0 ? server_timeout + std::chrono::seconds(5)
                                                              : maximum_wait_;
        const auto state = std::make_shared<CompletionState>();
        const auto request_accepted = std::make_shared<std::atomic_bool>(false);
        const bool accepted = client_.submit(std::move(request),
            [this, state, request_accepted, progress = control.progress, request_id](
                const grpc::Status& status, const proto::ServerMessage& message) {
            sources_.observe(message);
            if (status.ok() && message.has_job_accepted()) {
                request_accepted->store(true, std::memory_order_relaxed);
                report_progress(progress, request_id, "loading", true);
            } else if (status.ok() && message.has_job_progress()) {
                request_accepted->store(true, std::memory_order_relaxed);
                report_progress(progress, request_id, progress_stage(message.job_progress().stage()), true);
            }
            if (status.ok() && !JobProtocol::is_terminal(message)) return;
            {
                std::scoped_lock lock(state->mutex);
                if (state->done) return;
                state->status = status;
                if (status.ok()) state->terminal = message;
                state->done = true;
            }
            state->ready.notify_one();
        });
        if (!accepted) {
            sources_.retire(request_id);
            return ToolFailure{"QUEUE_FULL", "The bounded gRPC request registry is full.", true};
        }

        std::unique_lock lock(state->mutex);
        const auto deadline = std::chrono::steady_clock::now() + maximum_wait;
        while (!state->done && !control.stop.stop_requested() &&
            std::chrono::steady_clock::now() < deadline) {
            state->ready.wait_for(lock, std::chrono::milliseconds(100), [&state] { return state->done; });
        }
        if (control.stop.stop_requested() && !state->done) {
            lock.unlock();
            if (!client_.cancel(request_id, "Vibris profile workflow was cancelled.")) {
                lock.lock();
                state->ready.wait(lock, [&state] { return state->done; });
                lock.unlock();
            }
            sources_.retire(request_id);
            return request_failure(
                {"CANCELLED", "The Vibris profile workflow was cancelled.", false}, request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        if (!state->done) {
            lock.unlock();
            if (!client_.cancel(request_id, "Vibris job exceeded the local synchronous deadline.")) {
                lock.lock();
                state->ready.wait(lock, [&state] { return state->done; });
                lock.unlock();
            }
            sources_.retire(request_id);
            return request_failure(
                {"EXECUTION_TIMEOUT", "The Vibris job exceeded its total deadline.", true}, request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        const auto status = state->status;
        auto terminal = std::move(state->terminal);
        lock.unlock();
        sources_.retire(request_id);
        if (!status.ok()) {
            if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
                report_progress(control.progress, request_id, "loading", false);
                return transport_failure(status);
            }
            return request_failure(transport_failure(status), request_id,
                request_accepted->load(std::memory_order_relaxed));
        }
        if (!terminal) throw std::logic_error("gRPC completed without a terminal job message");
        report_progress(control.progress, request_id, "checkpointing", false);
        return JobProtocol::terminal(*terminal);
    } catch (...) {
        sources_.retire(request_id);
        throw;
    }
}

ToolOutcome SynchronousJobRunner::resume_once(std::string_view request_id,
    const SynchronousJobControl& control) {
    report_progress(control.progress, std::string(request_id), "loading", true);
    const auto state = std::make_shared<CompletionState>();
    const bool accepted = client_.resume(std::string(request_id),
        [state, progress = control.progress, id = std::string(request_id)](
            const grpc::Status& status, const proto::ServerMessage& message) {
            if (status.ok() && message.has_job_progress()) {
                report_progress(progress, id, progress_stage(message.job_progress().stage()), true);
            }
            if (status.ok() && !JobProtocol::is_terminal(message)) return;
            {
                std::scoped_lock lock(state->mutex);
                if (state->done) return;
                state->status = status;
                if (status.ok()) state->terminal = message;
                state->done = true;
            }
            state->ready.notify_one();
        });
    if (!accepted) {
        return request_failure(
            {"QUEUE_FULL", "The bounded gRPC request registry is full.", true}, request_id, true);
    }

    const auto maximum_wait = maximum_wait_.count() == 0 ? std::chrono::minutes(15) : maximum_wait_;
    std::unique_lock lock(state->mutex);
    const auto deadline = std::chrono::steady_clock::now() + maximum_wait;
    while (!state->done && !control.stop.stop_requested() &&
        std::chrono::steady_clock::now() < deadline) {
        state->ready.wait_for(lock, std::chrono::milliseconds(100), [&state] { return state->done; });
    }
    if (control.stop.stop_requested() && !state->done) {
        lock.unlock();
        if (!client_.cancel(request_id, "Vibris profile workflow was cancelled.")) {
            lock.lock();
            state->ready.wait(lock, [&state] { return state->done; });
            lock.unlock();
        }
        return request_failure(
            {"CANCELLED", "The Vibris profile workflow was cancelled.", false}, request_id, true);
    }
    if (!state->done) {
        lock.unlock();
        if (!client_.cancel(request_id, "Vibris resumed job exceeded the local synchronous deadline.")) {
            lock.lock();
            state->ready.wait(lock, [&state] { return state->done; });
            lock.unlock();
        }
        return request_failure(
            {"EXECUTION_TIMEOUT", "The resumed Vibris job exceeded its deadline.", true}, request_id, true);
    }
    const auto status = state->status;
    auto terminal = std::move(state->terminal);
    lock.unlock();
    if (!status.ok()) {
        if (status.error_code() == grpc::StatusCode::NOT_FOUND) {
            report_progress(control.progress, std::string(request_id), "loading", false);
            return transport_failure(status);
        }
        return request_failure(transport_failure(status), request_id, true);
    }
    if (!terminal) throw std::logic_error("gRPC resume completed without a terminal job message");
    report_progress(control.progress, std::string(request_id), "checkpointing", false);
    return JobProtocol::terminal(*terminal);
}

ToolOutcome SynchronousJobRunner::run(std::string_view tool_name, const Json& arguments,
    const proto::ServerHello& server, const proto::SceneContext& context,
    const SynchronousJobControl& control) {
    if (tool_name == "vibris_run_recipe") {
        const auto recipe = arguments.at("recipe").get<std::string>();
        if (recipe == "profile" || recipe == "profile_matrix") {
            bool first_attempt = true;
            return retry_profile(arguments, recipe == "profile_matrix", config_.default_warmup_frames,
                [this, &server, &context, &control, &first_attempt](const Json& attempt, bool matrix) -> ToolOutcome {
                    ToolOutcome outcome;
                    if (first_attempt && control.resume_request_id) {
                        outcome = resume_once(*control.resume_request_id, control);
                        if (auto* failure = std::get_if<ToolFailure>(&outcome);
                            failure != nullptr && failure->code == "CANCELLED" &&
                            (!failure->details.is_object() ||
                                !failure->details.value("resume_required", false))) {
                            *failure = ToolFailure{"SERVER_RESTARTED",
                                "The resumed request was cancelled and can be submitted safely again.", true};
                        }
                    } else {
                        if (!first_attempt) report_progress(control.progress, {}, "retrying", false);
                        outcome = submit_once("vibris_run_recipe", attempt, server, context, control);
                    }
                    first_attempt = false;
                    if (!std::holds_alternative<Json>(outcome)) return outcome;
                    return profile_result(std::get<Json>(outcome), attempt, config_, matrix);
                });
        }
        if (recipe == "benchmark_ab") {
            const auto workflow_id = detail::generate_uuid();
            return run_paired_benchmark(arguments, workflow_id, config_.default_warmup_frames,
                [this, &server, &context, &control](const Json& profile_arguments) -> ToolOutcome {
                    return retry_profile(profile_arguments, false, config_.default_warmup_frames,
                        [this, &server, &context, &control](const Json& attempt, bool) -> ToolOutcome {
                            auto outcome = submit_once("vibris_run_recipe", attempt, server, context, control);
                            if (!std::holds_alternative<Json>(outcome)) return outcome;
                            return profile_result(std::get<Json>(outcome), attempt, config_, false);
                        });
                },
                [this, &server, &context, &control](const Json& visual_arguments) -> ToolOutcome {
                    return submit_once("vibris_run_recipe", visual_arguments, server, context, control);
                });
        }
        auto outcome = control.resume_request_id
            ? resume_once(*control.resume_request_id, control)
            : submit_once(tool_name, arguments, server, context, control);
        if (auto* result = std::get_if<Json>(&outcome)) {
            (*result)["kind"] = recipe;
            if (recipe == "ab_compare") {
                const auto& captures = arguments.at("captures");
                const bool require_heatmap = std::ranges::any_of(captures, [](const Json& capture) {
                    return capture.is_object() &&
                        (capture.value("type", std::string{}) == "screenshot" ||
                            capture.value("format", std::string{}) == "png");
                });
                auto visual_guards = visual_comparison_guards(*result, require_heatmap);
                const auto comparison = result->find("comparison");
                if (!visual_guards.at("passed").get<bool>()) {
                    (*result)["status"] = "invalid_comparison";
                    (*result)["verdict"] = "inconclusive";
                    (*result)["success"] = false;
                    (*result)["error"] = {{"success", false},
                        {"error_code", "INVALID_VISUAL_RECEIPT"},
                        {"message", "The visual comparison receipt failed its deterministic-state guards."},
                        {"retryable", false}, {"details", visual_guards.at("mismatches")}};
                } else if (comparison != result->end() && comparison->is_object()) {
                    const auto passed = comparison->value("passed", true);
                    (*result)["status"] = passed ? "completed" : "completed_with_failures";
                    (*result)["verdict"] = comparison->value("verdict", std::string("not_evaluated"));
                    (*result)["success"] = passed;
                }
                (*result)["visual_guards"] = std::move(visual_guards);
            }
        }
        return outcome;
    }
    auto outcome = control.resume_request_id
        ? resume_once(*control.resume_request_id, control)
        : submit_once(tool_name, arguments, server, context, control);
    if (tool_name == "vibris_run_matrix" && std::holds_alternative<Json>(outcome)) {
        return matrix_result(std::get<Json>(outcome), arguments);
    }
    return outcome;
}

}
