#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace vibris::mcp {

inline constexpr std::size_t kMaxConfigJsonBytes = 64 * 1024;

struct SessionConfig final {
    std::uint32_t schema_version = 1;
    std::string workspace_id;
    std::string shader_directory = "shaders";
    std::string save_id;
    std::string dimension_id;
    std::string time_preset_id;
    std::string camera_preset_id;
    double fov = 70.0;
    std::uint32_t default_warmup_frames = 32;

    bool operator==(const SessionConfig&) const = default;
};

}
