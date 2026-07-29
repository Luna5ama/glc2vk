#pragma once

#include "config_store.hpp"
#include "grpc_client.hpp"
#include "phase_two_source_handler.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <chrono>
#include <string_view>

namespace vibris::mcp {

class SynchronousJobRunner final {
public:
    SynchronousJobRunner(GrpcClient& client, PhaseTwoSourceHandler& sources, const SessionConfig& config,
        std::chrono::milliseconds maximum_wait = std::chrono::milliseconds::zero());

    [[nodiscard]] ToolOutcome run(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v1::ServerHello& server,
        const ::vibris::control::v1::SceneContext& context);

private:
    GrpcClient& client_;
    PhaseTwoSourceHandler& sources_;
    const SessionConfig& config_;
    std::chrono::milliseconds maximum_wait_;
};

}