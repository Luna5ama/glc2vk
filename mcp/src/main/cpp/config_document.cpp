#include "config_document.hpp"

#include "state_error.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <limits>
#include <random>

namespace vibris::mcp::detail {
namespace {

using Json = nlohmann::json;

[[noreturn]] void invalid_config() {
    throw StateError(kInvalidConfigCode, "Config JSON is malformed.");
}

[[noreturn]] void unsupported_config() {
    throw StateError(kUnsupportedVersionCode, "UNSUPPORTED_VERSION: config schema_version must be 2.");
}

void validate_string(std::string_view value) {
    if (value.empty() || value.find('\0') != std::string_view::npos) {
        invalid_config();
    }
}

std::string required_string(const Json& document, std::string_view key) {
    const auto iterator = document.find(key);
    if (iterator == document.end() || !iterator->is_string()) {
        invalid_config();
    }
    return iterator->get<std::string>();
}

std::uint32_t required_unsigned(const Json& document, std::string_view key) {
    const auto iterator = document.find(key);
    if (iterator == document.end() || !iterator->is_number_unsigned()) {
        invalid_config();
    }
    const auto value = iterator->get<std::uint64_t>();
    if (value > std::numeric_limits<std::uint32_t>::max()) {
        invalid_config();
    }
    return static_cast<std::uint32_t>(value);
}

} // namespace

bool is_uuid(std::string_view value) {
    if (value.size() != 36) {
        return false;
    }
    for (std::size_t index = 0; index < value.size(); ++index) {
        const auto character = value[index];
        const auto separator = index == 8 || index == 13 || index == 18 || index == 23;
        if (separator ? character != '-' : !std::isxdigit(static_cast<unsigned char>(character))) {
            return false;
        }
    }
    return true;
}

void validate_config(const JobContext& config, bool workspace_required) {
    if (config.schema_version != 2) {
        unsupported_config();
    }
    if (config.shader_directory != "shaders" || !std::isfinite(config.fov) ||
        config.fov <= 0.0 || config.fov > 180.0 || (workspace_required && config.workspace_id.empty()) ||
        (!config.workspace_id.empty() && !is_uuid(config.workspace_id))) {
        invalid_config();
    }
    validate_string(config.save_id);
    validate_string(config.dimension_id);
    validate_string(config.time_preset_id);
    validate_string(config.camera_preset_id);
}

JobContext parse_config(std::string_view text, ConfigDocumentKind kind) {
    if (text.size() > kMaxConfigJsonBytes) {
        throw StateError(kRequestTooLargeCode, "Config JSON exceeds the 64 KiB limit.");
    }

    try {
        const auto document = Json::parse(text.begin(), text.end());
        if (!document.is_object()) {
            invalid_config();
        }
        constexpr std::array<std::string_view, 9> fields {
            "schema_version", "workspace_id", "shader_directory", "save_id", "dimension_id", "time_preset_id",
            "camera_preset_id", "fov", "default_warmup_frames",
        };
        for (const auto& [key, value] : document.items()) {
            static_cast<void>(value);
            if (std::find(fields.begin(), fields.end(), key) == fields.end()) {
                invalid_config();
            }
        }

        JobContext config;
        const auto managed_required = kind == ConfigDocumentKind::persisted;
        if (document.contains("schema_version")) {
            config.schema_version = required_unsigned(document, "schema_version");
        } else if (managed_required) {
            invalid_config();
        }
        if (document.contains("workspace_id")) {
            config.workspace_id = required_string(document, "workspace_id");
        } else if (managed_required) {
            invalid_config();
        }
        if (document.contains("shader_directory")) {
            config.shader_directory = required_string(document, "shader_directory");
        } else if (managed_required) {
            invalid_config();
        }
        config.save_id = required_string(document, "save_id");
        config.dimension_id = required_string(document, "dimension_id");
        config.time_preset_id = required_string(document, "time_preset_id");
        config.camera_preset_id = required_string(document, "camera_preset_id");
        const auto fov = document.find("fov");
        if (fov == document.end() || !fov->is_number()) {
            invalid_config();
        }
        config.fov = fov->get<double>();
        config.default_warmup_frames = required_unsigned(document, "default_warmup_frames");
        validate_config(config, managed_required);
        return config;
    } catch (const StateError&) {
        throw;
    } catch (const Json::exception&) {
        invalid_config();
    }
}

std::string serialize_config(const JobContext& config) {
    try {
        const Json document {
            {"schema_version", config.schema_version},
            {"workspace_id", config.workspace_id},
            {"shader_directory", config.shader_directory},
            {"save_id", config.save_id},
            {"dimension_id", config.dimension_id},
            {"time_preset_id", config.time_preset_id},
            {"camera_preset_id", config.camera_preset_id},
            {"fov", config.fov},
            {"default_warmup_frames", config.default_warmup_frames},
        };
        auto text = document.dump(2);
        if (text.size() > kMaxConfigJsonBytes) {
            throw StateError(kRequestTooLargeCode, "Serialized config exceeds the 64 KiB limit.");
        }
        return text;
    } catch (const StateError&) {
        throw;
    } catch (const Json::exception&) {
        invalid_config();
    }
}

std::string generate_uuid() {
    std::array<std::uint8_t, 16> bytes {};
    std::random_device random;
    for (auto& byte : bytes) {
        byte = static_cast<std::uint8_t>(random());
    }
    bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0f) | 0x40);
    bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3f) | 0x80);

    constexpr char hex[] = "0123456789abcdef";
    std::string uuid(36, '-');
    std::size_t output = 0;
    for (const auto byte : bytes) {
        if (output == 8 || output == 13 || output == 18 || output == 23) {
            ++output;
        }
        uuid[output++] = hex[byte >> 4];
        uuid[output++] = hex[byte & 0x0f];
    }
    return uuid;
}

} // namespace vibris::mcp::detail
