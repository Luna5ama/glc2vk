#pragma once

#include "session_config.hpp"
#include "grpc_client.hpp"
#include "source_handler.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <chrono>
#include <functional>
#include <optional>
#include <stop_token>
#include <string>
#include <string_view>

namespace vibris::mcp {

struct SynchronousJobProgress final {
    std::string request_id;
    std::string stage;
    bool accepted = false;
};

using SynchronousJobProgressSink = std::function<void(const SynchronousJobProgress&)>;

struct SynchronousJobControl final {
    std::stop_token stop;
    std::optional<std::string> resume_request_id;
    SynchronousJobProgressSink progress;
};

class SynchronousJobRunner final {
public:
    SynchronousJobRunner(GrpcClient& client, SourceHandler& sources, const SessionConfig& config,
        std::chrono::milliseconds maximum_wait = std::chrono::milliseconds::zero());

    [[nodiscard]] ToolOutcome run(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v1::ServerHello& server,
        const ::vibris::control::v1::SceneContext& context,
        const SynchronousJobControl& control = {});

private:
    [[nodiscard]] ToolOutcome submit_once(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v1::ServerHello& server,
        const ::vibris::control::v1::SceneContext& context,
        const SynchronousJobControl& control);

    [[nodiscard]] ToolOutcome resume_once(
        std::string_view request_id,
        const ::vibris::control::v1::ServerHello& server,
        const SynchronousJobControl& control);

    GrpcClient& client_;
    SourceHandler& sources_;
    const SessionConfig& config_;
    std::chrono::milliseconds maximum_wait_;
};

}
