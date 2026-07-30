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
        if (tool_name == "vibris_run_actions" && arguments.contains("source") &&
            std::holds_alternative<Json>(outcome)) {
            for (auto& result : std::get<Json>(outcome).at("action_results")) {
                const auto protocol_index = result.at("action_index").get<std::uint32_t>();
                if (protocol_index == 0) throw std::logic_error("runtime action result refers to source overlay");
                result["action_index"] = protocol_index - 1;
            }
        }
        if (tool_name == "vibris_run_recipe" && std::holds_alternative<Json>(outcome)) {
            std::get<Json>(outcome)["kind"] = arguments.at("recipe");
        }
        return outcome;
    } catch (...) {
        sources_.retire(request_id);
        throw;
    }
}

}
