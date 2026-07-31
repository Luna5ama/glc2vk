#include "tool_registry.hpp"

#include <array>
#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace {

using vibris::mcp::InvocationError;
using vibris::mcp::InvocationErrorCode;
using vibris::mcp::Json;
using vibris::mcp::ToolRegistry;

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

void schema_rejects_before_dispatch() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view, const Json&) {
        ++dispatches;
        return Json{{"accepted", true}};
    });
    const std::array forbidden{"run_shell", "load_source", "reload_shaderpack", "renderdoc_capture"};
    for (const auto* type : forbidden) {
        const Json arguments{{"actions", Json::array({{{"type", type}}})}};
        const auto result = registry.invoke("vibris_run_actions", arguments);
        const auto* error = std::get_if<InvocationError>(&result);
        require(error != nullptr && error->code == InvocationErrorCode::InvalidArguments,
            "Forbidden action was not rejected by schema validation.");
    }
    const Json absolute{{"actions", Json::array({{{"type", "capture_screenshot"},
                                                   {"artifact_name", "C:\\outside\\capture.png"}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", absolute)),
        "Absolute artifact path was not rejected.");
    const Json nested{{"actions", Json::array({{{"type", "capture_screenshot"},
                                                 {"artifact_name", "nested/capture"}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", nested)),
        "Nested artifact path was not rejected by the flat-name grammar.");
    require(dispatches == 0, "Forbidden action reached source preparation dispatch.");

    require(std::holds_alternative<Json>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array()}})),
        "Empty action sequence was rejected.");
    const Json flat{{"actions", Json::array({{{"type", "capture_texture"},
                                               {"name", "colortex5"},
                                               {"format", "raw"},
                                               {"artifact_name", "frame_1"}}})}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", flat)),
        "Safe flat artifact name was rejected.");
    require(dispatches == 2, "Allowed action sequences were not dispatched exactly once each.");
}

void registry_has_exactly_the_supported_tools() {
    ToolRegistry registry;
    std::set<std::string> names;
    for (const auto& definition : registry.definitions()) {
        names.insert(definition.at("name").get<std::string>());
    }
    const std::set<std::string> expected{
        "vibris_get_config", "vibris_list_presets", "vibris_configure",
        "vibris_get_status", "vibris_profile", "vibris_run_recipe", "vibris_run_actions"};
    require(registry.definitions().size() == expected.size() && names == expected,
        "Tool registry added a duplicate, atomic, submit, poll, or wait tool.");
    const std::array forbidden_tools{
        "vibris_submit_job", "vibris_get_job", "vibris_wait_job", "vibris_reload", "vibris_screenshot"};
    for (const auto* name : forbidden_tools) {
        const auto result = registry.invoke(name, Json::object());
        const auto* error = std::get_if<InvocationError>(&result);
        require(error != nullptr && error->code == InvocationErrorCode::UnknownTool,
            "Forbidden duplicate MCP tool was exposed.");
    }
}

void atomic_action_schemas_reject_invalid_arguments() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view, const Json&) {
        ++dispatches;
        return Json{{"accepted", true}};
    });

    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({{{"type", "capture_pass"}}})}})),
        "Capture pass accepted a missing pass name.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "schedule_screenshot"}, {"frames", 0}}})}})),
        "Screenshot scheduling accepted zero frames.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "get_gpu_metrics"}}})}})),
        "GPU metrics accepted a missing frame count.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "get_gpu_metrics"}, {"frames", 0}}})}})),
        "GPU metrics accepted zero frames.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "get_gpu_metrics"}, {"frames", 10'001}}})}})),
        "GPU metrics accepted more than the bounded frame count.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}}})}})),
        "Texture dump accepted no texture selector.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_ssbo"}}})}})),
        "SSBO dump accepted a missing binding index.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}, {"name", "colortex0"}, {"id", 1}}})}})),
        "Texture dump accepted two texture selectors.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "capture_pass"}, {"pass", "prepare"}, {"path", "../outside"}}})}})),
        "Capture pass accepted a path that escapes the game directory.");
    require(std::holds_alternative<Json>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}, {"name", "colortex0"}, {"raw", true}}})}})),
        "Texture dump rejected a valid logical name.");
    require(std::holds_alternative<Json>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "reload_shader"},
                     {"config", {{"SETTING_SAMPLE_COUNT", 32}, {"SETTING_CLOUDS", false}}}}})}})),
        "Shader reload rejected scalar config values.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "reload_shader"},
                     {"config", {{"SETTING_SAMPLE_COUNT", "32\nINJECTED=true"}}}}})}})),
        "Shader reload accepted a line-breaking config value.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {
                    {"config", {{"SETTING_SAMPLE_COUNT", 32}}}, {"actions", Json::array()}})),
        "Source-free actions silently accepted an unused top-level shader config.");
    require(dispatches == 2, "Invalid debug arguments reached dispatch.");
}

void empty_tool_schemas_declare_object_properties() {
    ToolRegistry registry;
    const std::set<std::string> empty_schema_tools{
        "vibris_get_config", "vibris_get_status"};
    for (const auto& definition : registry.definitions()) {
        const auto name = definition.at("name").get<std::string>();
        if (!empty_schema_tools.contains(name)) continue;

        const auto& properties = definition.at("inputSchema").at("properties");
        require(properties.is_object(), "Empty tool schema serialized properties as null.");
        require(properties.empty(), "Empty tool schema unexpectedly declared properties.");
    }
}

void profile_schema_requires_bounded_future_frames() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view name, const Json& arguments) {
        ++dispatches;
        require(name == "vibris_profile", "Profile schema dispatched the wrong tool.");
        require(arguments.at("frames") == 64, "Profile schema changed the frame count.");
        return Json{{"accepted", true}};
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_profile", Json::object())),
        "Profile accepted a missing frame count.");
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_profile", {{"frames", 0}})),
        "Profile accepted zero frames.");
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_profile", {{"frames", 10'001}})),
        "Profile accepted too many frames.");
    require(std::holds_alternative<Json>(registry.invoke("vibris_profile", {
        {"source", {{"kind", "workspace"}}},
        {"config", {{"SETTING_PARALLAX_MODE", 0}}},
        {"warmup_frames", 32},
        {"frames", 64},
    })), "Profile rejected a valid direct runtime measurement.");
    require(dispatches == 1, "Invalid profile arguments reached dispatch.");
}

void registry_declares_accurate_tool_annotations() {
    ToolRegistry registry;
    const std::set<std::string> read_only{
        "vibris_get_config", "vibris_list_presets", "vibris_get_status"};
    for (const auto& definition : registry.definitions()) {
        const auto& annotations = definition.at("annotations");
        const auto name = definition.at("name").get<std::string>();
        require(annotations.at("readOnlyHint") == read_only.contains(name), "Tool has the wrong read-only annotation.");
        require(annotations.at("destructiveHint") == false, "Vibris tools must not claim destructive behavior.");
        require(annotations.at("openWorldHint") == false, "Vibris tools must not claim open-world behavior.");
    }
}

}

int main() {
    try {
        schema_rejects_before_dispatch();
        registry_has_exactly_the_supported_tools();
        empty_tool_schemas_declare_object_properties();
        atomic_action_schemas_reject_invalid_arguments();
        profile_schema_requires_bounded_future_frames();
        registry_declares_accurate_tool_annotations();
        std::cout << "PASS ActionSchemaRejectsForbiddenAndDuplicateTools\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaRejectsForbiddenAndDuplicateTools: " << error.what() << '\n';
        return 1;
    }
}
