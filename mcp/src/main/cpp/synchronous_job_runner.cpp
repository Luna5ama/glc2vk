#include "synchronous_job_runner.hpp"

#include "config_document.hpp"
#include "job_protocol.hpp"

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
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

Json profile_result(const Json& job, const Json& arguments, const SessionConfig& config) {
    for (const auto& action : job.at("action_results")) {
        if (action.at("kind") != "get_gpu_metrics") continue;
        auto result = action.at("result");
        result["frames"] = arguments.at("frames");
        result["warmup_frames"] = arguments.value("warmup_frames", config.default_warmup_frames);
        return result;
    }
    throw std::logic_error("GPU metrics action result is missing");
}

bool failed_action(const Json& action) {
    const auto& result = action.at("result");
    return result.is_object() && result.contains("success") && result.at("success").is_boolean() &&
        !result.at("success").get<bool>();
}

Json matrix_result(Json job, const Json& arguments, const SessionConfig& config, bool profile) {
    const auto warmup = arguments.value("warmup_frames", config.default_warmup_frames);
    const std::size_t template_size = profile ? (warmup == 0 ? 2 : 3) : arguments.at("actions").size();
    const std::size_t case_size = template_size + 1;
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
            Json metrics = nullptr;
            Json error = nullptr;
            for (const auto& action : job.at("action_results")) {
                const auto index = action.at("action_index").get<std::size_t>();
                if (index < first || index >= last) continue;
                auto mapped = action;
                mapped["action_index"] = index - first;
                if (failed_action(action) && error.is_null()) error = action.at("result");
                if (action.at("kind") == "get_gpu_metrics") metrics = action.at("result");
                results.push_back(std::move(mapped));
            }
            Json case_artifacts = Json::array();
            for (const auto& artifact : job.at("artifacts")) {
                if (artifact.at("file_name").get_ref<const std::string&>().starts_with(case_id + "--")) {
                    case_artifacts.push_back(artifact);
                }
            }
            const bool failed = !error.is_null();
            if (failed) ++failures;
            Json item{{"id", case_id},
                      {"source", source_id},
                      {"config", config_id},
                      {"status", failed ? "failed" : "passed"},
                      {"error", std::move(error)},
                      {"action_results", std::move(results)},
                      {"artifacts", std::move(case_artifacts)}};
            if (profile) {
                item["metrics"] = std::move(metrics);
                item["frames"] = arguments.at("frames");
                item["warmup_frames"] = warmup;
            }
            cases.push_back(std::move(item));
            ++case_index;
        }
    }
    job["kind"] = profile ? "profile_matrix" : "matrix";
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
            if (recipe == "profile") return profile_result(std::get<Json>(outcome), arguments, config_);
            if (recipe == "profile_matrix") {
                return matrix_result(std::get<Json>(outcome), arguments, config_, true);
            }
            std::get<Json>(outcome)["kind"] = recipe;
        }
        if (tool_name == "vibris_run_matrix" && std::holds_alternative<Json>(outcome)) {
            return matrix_result(std::get<Json>(outcome), arguments, config_, false);
        }
        return outcome;
    } catch (...) {
        sources_.retire(request_id);
        throw;
    }
}

}
