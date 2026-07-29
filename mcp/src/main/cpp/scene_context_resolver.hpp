#pragma once

#include "config_store.hpp"
#include "vibris_control.pb.h"

namespace vibris::mcp {

class SceneContextResolver final {
public:
    [[nodiscard]] static ::vibris::control::v1::SceneContext resolve(
        const SessionConfig& config,
        const ::vibris::control::v1::ListPresetsResponse& response);
};

}