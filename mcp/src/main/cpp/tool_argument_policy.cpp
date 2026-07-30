#include "tool_argument_policy.hpp"

#include "config_store.hpp"

#include <algorithm>
#include <cstddef>
#include <filesystem>
#include <string>

namespace vibris::mcp {
namespace {

bool safe_flat_name(std::string_view value) {
    if (value.empty() || value.size() > 128 || value == "." || value == "..") return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-';
    });
}

bool safe_config_key(std::string_view value) {
    if (value.empty() || value.size() > 128 ||
        !((value.front() >= 'A' && value.front() <= 'Z') ||
          (value.front() >= 'a' && value.front() <= 'z') || value.front() == '_')) return false;
    return std::all_of(value.begin() + 1, value.end(), [](unsigned char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '_';
    });
}

bool safe_config_value(const Json& value) {
    if (value.is_boolean() || value.is_number()) return true;
    if (!value.is_string()) return false;
    const auto& text = value.get_ref<const std::string&>();
    return text.size() <= 4096 && std::all_of(text.begin(), text.end(), [](unsigned char character) {
        return character >= 0x20 && character <= 0x7e;
    });
}

bool safe_relative_path(std::string_view value) {
    if (value.empty()) return false;
    const std::filesystem::path path{std::string(value)};
    if (path.is_absolute() || path.has_root_name() || path.has_root_directory()) return false;
    return std::none_of(path.begin(), path.end(), [](const std::filesystem::path& component) {
        return component == "..";
    });
}

std::optional<InvocationError> validate_shader_config(const Json& arguments) {
    const auto config = arguments.find("config");
    if (config == arguments.end() || !config->is_object()) return std::nullopt;
    if (config->size() > 1024 || config->dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, "Shader config exceeds its size limit."};
    }
    for (const auto& [key, value] : config->items()) {
        if (!safe_config_key(key) || !safe_config_value(value)) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "arguments.config must contain safe option names and scalar ASCII values"};
        }
    }
    return std::nullopt;
}

}

std::optional<InvocationError> validate_argument_policy(std::string_view tool_name, const Json& arguments) {
    if (tool_name == "vibris_configure" && arguments.dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, "Config JSON exceeds the 64 KiB limit.",
                               {{"code", "REQUEST_TOO_LARGE"}, {"retryable", false}}};
    }
    if (arguments.is_object()) {
        if (const auto error = validate_shader_config(arguments)) return error;
    }
    if (tool_name != "vibris_run_actions" || !arguments.is_object()) return std::nullopt;
    if (arguments.contains("config") && !arguments.contains("source")) {
        return InvocationError{InvocationErrorCode::InvalidArguments,
            "arguments.config requires source; use a reload_shader action to configure the current runtime"};
    }
    const auto actions = arguments.find("actions");
    if (actions == arguments.end() || !actions->is_array()) return std::nullopt;
    for (std::size_t index = 0; index < actions->size(); ++index) {
        if (!(*actions)[index].is_object()) continue;
        if (const auto error = validate_shader_config((*actions)[index])) return error;
        const auto name = (*actions)[index].find("artifact_name");
        if (name != (*actions)[index].end() && name->is_string() &&
            !safe_flat_name(name->get_ref<const std::string&>())) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "arguments.actions[" + std::to_string(index) + "].artifact_name must be a safe flat name"};
        }
        const auto path = (*actions)[index].find("path");
        if (path != (*actions)[index].end() && path->is_string() &&
            !safe_relative_path(path->get_ref<const std::string&>())) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "arguments.actions[" + std::to_string(index) + "].path must be relative and remain in the game directory"};
        }
    }
    return std::nullopt;
}

}
