#pragma once

#include "vibris_control.pb.h"

#include <nlohmann/json_fwd.hpp>

namespace vibris::mcp {

class ResultMapper final {
public:
    [[nodiscard]] static nlohmann::json list_presets(
        const ::vibris::control::v1::ListPresetsResponse& response);
    [[nodiscard]] static nlohmann::json validation(
        const ::vibris::control::v1::ValidateContextResponse& response);
    [[nodiscard]] static nlohmann::json status(
        const ::vibris::control::v1::GetStatusResponse& response);
    [[nodiscard]] static nlohmann::json server_message(
        const ::vibris::control::v1::ServerMessage& response);
};

}