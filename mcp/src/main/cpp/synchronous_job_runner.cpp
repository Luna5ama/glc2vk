#include "synchronous_job_runner.hpp"

#include "config_document.hpp"
#include "job_protocol.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v1;

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

bool failed_action(const Json& action) {
    const auto& result = action.at("result");
    return result.is_object() && result.contains("success") && result.at("success").is_boolean() &&
        !result.at("success").get<bool>();
}

bool has_gpu_samples(const Json& result) {
    if (!result.is_object()) return false;
    const auto timings = result.find("gpuTimings");
    return timings != result.end() && timings->is_object() && !timings->empty();
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

bool selected_statistic(const Json& arguments, std::string_view name) {
    const auto filter = arguments.find("statistics");
    if (filter == arguments.end()) return true;
    return std::ranges::any_of(*filter, [name](const Json& statistic) {
        return statistic.get_ref<const std::string&>() == name;
    });
}

Json filtered_metrics(const Json& metrics, const Json& arguments) {
    Json result = metrics;
    Json filtered_timings = Json::object();
    for (const auto& [metric_name, statistics] : metrics.at("gpuTimings").items()) {
        if (!selected_metric(arguments, metric_name)) continue;
        if (!statistics.is_object()) {
            filtered_timings[metric_name] = statistics;
            continue;
        }
        Json filtered_statistics = Json::object();
        for (const auto& [statistic, value] : statistics.items()) {
            if (!selected_statistic(arguments, statistic)) continue;
            filtered_statistics[statistic] = value;
            if (!value.is_number()) continue;
            const auto nanoseconds = value.get<double>();
            for (const auto& unit : arguments.value("converted_units", Json::array())) {
                const auto& name = unit.get_ref<const std::string&>();
                if (name == "us") filtered_statistics[statistic + "_us"] = nanoseconds / 1'000.0;
                if (name == "ms") filtered_statistics[statistic + "_ms"] = nanoseconds / 1'000'000.0;
            }
        }
        filtered_timings[metric_name] = std::move(filtered_statistics);
    }
    result["gpuTimings"] = std::move(filtered_timings);
    return result;
}

Json no_gpu_samples_error(std::string_view case_id, std::string_view reason) {
    return {{"success", false},
            {"error_code", "NO_GPU_SAMPLES"},
            {"message", "GPU metrics did not return a non-empty gpuTimings object."},
            {"retryable", true},
            {"details", {{"case_id", case_id}, {"reason", reason}}}};
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
    std::size_t first_action, std::size_t last_action, bool matrix, std::string_view detail) {
    const bool full = detail == "full";
    const bool include_metrics = detail != "summary";
    Json action_results = Json::array();
    Json metrics = nullptr;
    Json error = nullptr;
    bool metrics_seen = false;
    for (const auto& action : job.at("action_results")) {
        const auto index = action.at("action_index").get<std::size_t>();
        if (index < first_action || index >= last_action) continue;
        if (failed_action(action) && error.is_null()) error = action.at("result");
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

    ++counts.requested;
    const bool has_metrics = !metrics.is_null();
    if (has_metrics) ++counts.with_metrics;
    const bool failed = !error.is_null();
    const bool incomplete = !failed && !has_metrics;
    const char* status = "passed";
    if (failed) {
        ++counts.failed;
        status = "failed";
    } else if (incomplete) {
        ++counts.incomplete;
        status = "incomplete";
        error = no_gpu_samples_error(
            case_id, metrics_seen ? "empty_gpu_timings" : "missing_gpu_metrics_action");
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
              {"metrics", std::move(visible_metrics)}};
    if (full) {
        item["action_results"] = std::move(action_results);
        item["artifacts"] = case_artifacts(job, item.at("case_id").get<std::string>(), matrix);
    }
    cases.push_back(std::move(item));
}

void append_full_job_metadata(const Json& job, Json& result) {
    constexpr std::array fields{
        "diagnostics", "comparison", "timings", "frame_ids", "artifacts",
        "artifact_groups", "manifest_path",
    };
    for (const auto* field : fields) {
        const auto value = job.find(field);
        if (value != job.end()) result[field] = *value;
    }
}

Json profile_result(Json job, const Json& arguments, const SessionConfig& config, bool matrix) {
    const auto detail = arguments.value("result_detail", std::string("metrics"));
    const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
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
                    source_id + "--" + config_id, source_id, config_id, first, first + case_size, true, detail);
                ++case_index;
            }
        }
    } else {
        append_profile_case(job, cases, counts, arguments, warmup, "source--config", "source", "config",
            0, std::numeric_limits<std::size_t>::max(), false, detail);
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
                if (index < first || index >= last) continue;
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

}

SynchronousJobRunner::SynchronousJobRunner(
    GrpcClient& client, SourceHandler& sources, const SessionConfig& config,
    const std::chrono::milliseconds maximum_wait)
    : client_(client), sources_(sources), config_(config), maximum_wait_(maximum_wait) {
    if (maximum_wait_.count() < 0) throw std::invalid_argument("maximum wait must not be negative");
}

ToolOutcome SynchronousJobRunner::run(std::string_view tool_name, const Json& arguments,
    const proto::ServerHello& server, const proto::SceneContext& context) {
    const auto request_id = detail::generate_uuid();
    sources_.prepare(tool_name, arguments, server);
    const auto references = sources_.bind_latest(request_id);
    try {
        auto request = JobProtocol::request(tool_name, arguments, config_, context, references, request_id);
        const auto server_timeout = std::chrono::milliseconds(request.submit_job().timeouts().total_timeout_ms());
        const auto maximum_wait = maximum_wait_.count() == 0 ? server_timeout + std::chrono::seconds(5)
                                                              : maximum_wait_;
        const auto state = std::make_shared<CompletionState>();
        const bool accepted = client_.submit(std::move(request), [this, state](
            const grpc::Status& status, const proto::ServerMessage& message) {
            sources_.observe(message);
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
        if (!state->ready.wait_for(lock, maximum_wait, [&state] { return state->done; })) {
            lock.unlock();
            if (!client_.cancel(request_id, "Vibris job exceeded the local synchronous deadline.")) {
                lock.lock();
                state->ready.wait(lock, [&state] { return state->done; });
                lock.unlock();
            }
            sources_.retire(request_id);
            return ToolFailure{"EXECUTION_TIMEOUT", "The Vibris job exceeded its total deadline.", true};
        }
        const auto status = state->status;
        auto terminal = std::move(state->terminal);
        lock.unlock();
        sources_.retire(request_id);
        if (!status.ok()) return transport_failure(status);
        if (!terminal) throw std::logic_error("gRPC completed without a terminal job message");
        auto outcome = JobProtocol::terminal(*terminal);
        if (tool_name == "vibris_run_recipe" && std::holds_alternative<Json>(outcome)) {
            const auto recipe = arguments.at("recipe").get<std::string>();
            if (recipe == "profile") {
                return profile_result(std::get<Json>(outcome), arguments, config_, false);
            }
            if (recipe == "profile_matrix") {
                return profile_result(std::get<Json>(outcome), arguments, config_, true);
            }
            std::get<Json>(outcome)["kind"] = recipe;
        }
        if (tool_name == "vibris_run_matrix" && std::holds_alternative<Json>(outcome)) {
            return matrix_result(std::get<Json>(outcome), arguments);
        }
        return outcome;
    } catch (...) {
        sources_.retire(request_id);
        throw;
    }
}

}
