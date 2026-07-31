#pragma once

#include "session_config.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <span>
#include <string>
#include <string_view>

namespace vibris::mcp {

class JobProtocol final {
public:
    [[nodiscard]] static ::vibris::control::v1::ClientMessage request(
        std::string_view tool_name,
        const Json& arguments,
        const SessionConfig& config,
        const ::vibris::control::v1::SceneContext& context,
        std::span<const ::vibris::control::v1::PreparedSourceRef> sources,
        std::string request_id);
    [[nodiscard]] static bool is_terminal(const ::vibris::control::v1::ServerMessage& message) noexcept;
    [[nodiscard]] static ToolOutcome terminal(const ::vibris::control::v1::ServerMessage& message);
};

}
