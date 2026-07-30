#pragma once

#include <optional>
#include <string_view>

#include <nlohmann/json_fwd.hpp>

#include "vibris_control.pb.h"

namespace vibris::mcp {

class DebugProtocol final {
public:
    [[nodiscard]] static std::optional<::vibris::control::v1::DebugControlRequest> request(
        std::string_view tool_name, const nlohmann::json& arguments);
    [[nodiscard]] static nlohmann::json response(
        const ::vibris::control::v1::DebugControlResponse& response);
};

}
