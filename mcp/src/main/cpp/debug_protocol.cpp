#include "debug_protocol.hpp"

#include <cstdint>
#include <stdexcept>
#include <string>

#include <nlohmann/json.hpp>

namespace vibris::mcp {
namespace {

namespace control = ::vibris::control::v1;

control::DebugOperation operation(const std::string_view name) {
    if (name == "vibris_get_capture_status") return control::DEBUG_OPERATION_CAPTURE_STATUS;
    if (name == "vibris_reload_shader") return control::DEBUG_OPERATION_RELOAD_SHADER;
    if (name == "vibris_capture_pass") return control::DEBUG_OPERATION_CAPTURE_PASS;
    if (name == "vibris_capture_multi") return control::DEBUG_OPERATION_CAPTURE_MULTI;
    if (name == "vibris_get_shader_status") return control::DEBUG_OPERATION_SHADER_STATUS;
    if (name == "vibris_get_shader_errors") return control::DEBUG_OPERATION_SHADER_ERRORS;
    if (name == "vibris_schedule_screenshot") return control::DEBUG_OPERATION_SCHEDULE_SCREENSHOT;
    if (name == "vibris_get_screenshot_result") return control::DEBUG_OPERATION_SCREENSHOT_RESULT;
    if (name == "vibris_get_gpu_metrics") return control::DEBUG_OPERATION_GPU_METRICS;
    if (name == "vibris_list_ssbos") return control::DEBUG_OPERATION_LIST_SSBOS;
    if (name == "vibris_dump_ssbo") return control::DEBUG_OPERATION_DUMP_SSBO;
    if (name == "vibris_list_textures") return control::DEBUG_OPERATION_LIST_TEXTURES;
    if (name == "vibris_dump_texture") return control::DEBUG_OPERATION_DUMP_TEXTURE;
    if (name == "vibris_list_patched_shaders") return control::DEBUG_OPERATION_LIST_PATCHED_SHADERS;
    throw std::invalid_argument("Unknown debug tool");
}

}

control::DebugControlRequest DebugProtocol::request(
    const std::string_view tool_name, const nlohmann::json& arguments) {
    control::DebugControlRequest request;
    request.set_operation(operation(tool_name));
    if (tool_name == "vibris_capture_pass") request.set_pass(arguments.at("pass").get<std::string>());
    if (tool_name == "vibris_capture_multi") request.set_capture_type(arguments.at("type").get<std::string>());
    if (arguments.contains("path")) request.set_path(arguments.at("path").get<std::string>());
    if (tool_name == "vibris_schedule_screenshot") request.set_frames(arguments.value("frames", 1));
    if (tool_name == "vibris_dump_ssbo") request.set_index(arguments.value("index", 0));
    if (tool_name == "vibris_dump_texture") {
        if (arguments.contains("name")) request.set_texture_name(arguments.at("name").get<std::string>());
        if (arguments.contains("id")) request.set_texture_id(arguments.at("id").get<std::uint32_t>());
        request.set_raw(arguments.value("raw", false));
    }
    return request;
}

nlohmann::json DebugProtocol::response(const control::DebugControlResponse& response) {
    auto result = nlohmann::json::parse(response.json());
    if (!result.is_object()) throw std::runtime_error("Debug control returned a non-object response");
    return result;
}

}
