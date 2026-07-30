#include "debug_protocol.hpp"

#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>

#include <nlohmann/json.hpp>

namespace vibris::mcp {
namespace control = ::vibris::control::v1;
namespace {

std::string config_value(const nlohmann::json& value) {
    return value.is_string() ? value.get<std::string>() : value.dump();
}

void copy_config(const nlohmann::json& arguments, control::ShaderConfig& output) {
    for (const auto& [key, value] : arguments.at("config").items()) {
        (*output.mutable_values())[key] = config_value(value);
    }
}

}

std::optional<control::DebugControlRequest> DebugProtocol::request(
    const std::string_view tool_name, const nlohmann::json& arguments) {
    control::DebugControlRequest request;
    if (tool_name == "vibris_get_capture_status") request.mutable_capture_status();
    else if (tool_name == "vibris_reload_shader") {
        auto& command = *request.mutable_reload_shader();
        if (arguments.contains("config")) copy_config(arguments, *command.mutable_config());
    }
    else if (tool_name == "vibris_capture_pass") {
        auto& command = *request.mutable_capture_pass();
        command.set_pass(arguments.at("pass").get<std::string>());
        if (arguments.contains("path")) command.set_path(arguments.at("path").get<std::string>());
    } else if (tool_name == "vibris_capture_multi") {
        auto& command = *request.mutable_capture_multi();
        command.set_type(arguments.at("type").get<std::string>());
        if (arguments.contains("path")) command.set_path(arguments.at("path").get<std::string>());
    } else if (tool_name == "vibris_get_shader_status") request.mutable_shader_status();
    else if (tool_name == "vibris_get_shader_errors") request.mutable_shader_errors();
    else if (tool_name == "vibris_schedule_screenshot") {
        request.mutable_schedule_screenshot()->set_frames(arguments.value("frames", 1));
    } else if (tool_name == "vibris_get_screenshot_result") request.mutable_screenshot_result();
    else if (tool_name == "vibris_get_gpu_metrics") request.mutable_gpu_metrics();
    else if (tool_name == "vibris_list_ssbos") request.mutable_list_ssbos();
    else if (tool_name == "vibris_dump_ssbo") {
        request.mutable_dump_ssbo()->set_index(arguments.at("index").get<std::uint32_t>());
    } else if (tool_name == "vibris_list_textures") request.mutable_list_textures();
    else if (tool_name == "vibris_dump_texture") {
        auto& command = *request.mutable_dump_texture();
        if (arguments.contains("name")) command.set_name(arguments.at("name").get<std::string>());
        else command.set_id(arguments.at("id").get<std::uint32_t>());
        command.set_raw(arguments.value("raw", false));
    } else if (tool_name == "vibris_list_patched_shaders") request.mutable_list_patched_shaders();
    else return std::nullopt;
    return request;
}

nlohmann::json DebugProtocol::response(const control::DebugControlResponse& response) {
    auto result = nlohmann::json::parse(response.json());
    if (!result.is_object()) throw std::runtime_error("Debug control returned a non-object response");
    return result;
}

}
