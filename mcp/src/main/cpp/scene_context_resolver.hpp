#pragma once

#include "job_context.hpp"
#include "vibris_control.pb.h"

#include <string_view>

namespace vibris::mcp {

class SceneContextResolver final {
public:
    [[nodiscard]] static ::vibris::control::v1::ScenePreset resolve_preset(
        const JobContext& config,
        const ::vibris::control::v1::ListPresetsResponse& response);
    [[nodiscard]] static ::vibris::control::v1::ScenePreset resolve_preset(
        std::string_view preset_id,
        const ::vibris::control::v1::ListPresetsResponse& response);
    [[nodiscard]] static ::vibris::control::v1::SceneContext resolve(
        const JobContext& config,
        const ::vibris::control::v1::ListPresetsResponse& response);
};

}
