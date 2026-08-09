#pragma once

#include "session_config.hpp"
#include "vibris_control.pb.h"

#include <string_view>

namespace vibris::mcp {

class SceneContextResolver final {
public:
    [[nodiscard]] static ::vibris::control::v1::ScenePreset resolve_preset(
        const SessionConfig& config,
        const ::vibris::control::v1::ListPresetsResponse& response);
    [[nodiscard]] static ::vibris::control::v1::ScenePreset resolve_preset(
        std::string_view preset_id,
        const ::vibris::control::v1::ListPresetsResponse& response);
    [[nodiscard]] static ::vibris::control::v1::SceneContext resolve(
        const SessionConfig& config,
        const ::vibris::control::v1::ListPresetsResponse& response);
};

}
