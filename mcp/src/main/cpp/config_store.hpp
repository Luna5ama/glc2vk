#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

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

class ConfigStore final {
public:
    explicit ConfigStore(std::filesystem::path config_path);

    [[nodiscard]] const std::filesystem::path& path() const noexcept;
    [[nodiscard]] std::optional<SessionConfig> load() const;
    void save(const SessionConfig& config) const;
    [[nodiscard]] SessionConfig save_json(std::string_view json_text) const;

private:
    [[nodiscard]] SessionConfig prepare_for_save(SessionConfig config) const;
    void write_atomic(const SessionConfig& config) const;

    std::filesystem::path config_path_;
};

} // namespace vibris::mcp