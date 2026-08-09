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
    const std::array forbidden{
        "run_shell", "load_source", "reload_shaderpack", "renderdoc_capture", "reset_temporal_state"};
    for (const auto* type : forbidden) {
        const Json arguments{{"actions", Json::array({{{"type", type}}})}};
        const auto result = registry.invoke("vibris_run_actions", arguments);
        const auto* error = std::get_if<InvocationError>(&result);
        require(error != nullptr && error->code == InvocationErrorCode::InvalidArguments,
            "Forbidden action was not rejected by schema validation.");
    }
    const Json absolute{{"actions", Json::array({{{"type", "take_screenshot"},
                                                   {"artifact_name", "C:\\outside\\capture.png"}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", absolute)),
        "Absolute artifact path was not rejected.");
    const Json nested{{"actions", Json::array({{{"type", "take_screenshot"},
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
        "vibris_get_status", "vibris_run_recipe", "vibris_run_actions", "vibris_run_matrix"};
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
                    {{"type", "take_screenshot"}, {"after_frames", -1}}})}})),
        "Screenshot accepted a negative delay.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "take_screenshot"}, {"after_frames", 2'147'483'648LL}}})}})),
        "Screenshot accepted a delay outside the runtime integer range.");
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
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", {
        {"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "quality"},
            {"values", {{"SETTING_SAMPLE_COUNT", 32}, {"SETTING_CLOUDS", false}}}}})},
        {"actions", Json::array({{{"type", "load_shader"},
            {"source", "candidate"}, {"config", "quality"}}})},
    })), "Shader load rejected named source and config references.");
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

void registry_exposes_canonical_load_workflows() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view, const Json&) {
        ++dispatches;
        return Json{{"accepted", true}};
    });
    require(std::holds_alternative<Json>(registry.invoke(
        "vibris_run_recipe", {{"recipe", "load_and_screenshot"}})),
        "Canonical screenshot recipe was rejected.");
    require(std::holds_alternative<InvocationError>(registry.invoke(
        "vibris_run_recipe", {{"recipe", "reload_and_capture"}})),
        "Legacy reload recipe remained exposed in the MCP schema.");
    require(dispatches == 1, "Rejected legacy recipe reached dispatch.");

    bool load_description = false;
    bool matrix_description = false;
    for (const auto& definition : registry.definitions()) {
        const auto name = definition.at("name").get<std::string>();
        if (name == "vibris_run_actions") {
            const auto& variants = definition.at("inputSchema").at("properties")
                .at("actions").at("items").at("oneOf");
            for (const auto& variant : variants) {
                if (!variant.contains("description")) continue;
                const auto description = variant.at("description").get<std::string>();
                load_description = description.find("Closes any open screen") != std::string::npos &&
                    description.find("hides the HUD") != std::string::npos &&
                    description.find("resets temporal counters") != std::string::npos;
            }
        } else if (name == "vibris_run_matrix") {
            const auto description = definition.at("description").get<std::string>();
            matrix_description = description.find("automatically begins with load_shader") != std::string::npos &&
                description.find("do not include load_shader in the action template") != std::string::npos;
        }
    }
    require(load_description, "load_shader did not describe its atomic view and temporal guarantees.");
    require(matrix_description, "Matrix did not describe its implicit load_shader action.");
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

void profile_recipe_requires_bounded_future_frames() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view name, const Json& arguments) {
        ++dispatches;
        require(name == "vibris_run_recipe", "Profile recipe dispatched the wrong tool.");
        require(arguments.at("frames") == 64, "Profile schema changed the frame count.");
        return Json{{"accepted", true}};
    });
    require(std::holds_alternative<InvocationError>(registry.invoke(
        "vibris_run_recipe", {{"recipe", "profile"}})),
        "Profile accepted a missing frame count.");
    require(std::holds_alternative<InvocationError>(registry.invoke(
        "vibris_run_recipe", {{"recipe", "profile"}, {"frames", 0}})),
        "Profile accepted zero frames.");
    require(std::holds_alternative<InvocationError>(registry.invoke(
        "vibris_run_recipe", {{"recipe", "profile"}, {"frames", 10'001}})),
        "Profile accepted too many frames.");
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", {
        {"recipe", "profile"},
        {"source", {{"kind", "workspace"}}},
        {"config", {{"SETTING_PARALLAX_MODE", 0}}},
        {"warmup_frames", 32},
        {"frames", 64},
    })), "Profile rejected a valid direct runtime measurement.");
    require(dispatches == 1, "Invalid profile arguments reached dispatch.");
}

void matrix_schema_requires_named_sources_configs_and_axes() {
    std::size_t dispatches = 0;
    ToolRegistry registry([&](std::string_view name, const Json&) {
        ++dispatches;
        require(name == "vibris_run_matrix", "Matrix schema dispatched the wrong tool.");
        return Json{{"accepted", true}};
    });
    const Json valid{
        {"sources", Json::array({{{"id", "base"}, {"kind", "commit"}, {"revision", "HEAD~1"}},
                                 {{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "steep"}, {"values", {{"SETTING_PARALLAX_MODE", 1}}}},
                                 {{"id", "spline"}, {"values", {{"SETTING_PARALLAX_MODE", 4}}}}})},
        {"matrix", {{"sources", Json::array({"base", "candidate"})},
                    {"configs", Json::array({"steep", "spline"})}}},
        {"actions", Json::array({{{"type", "get_gpu_metrics"}, {"frames", 64}}})},
    };
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_matrix", valid)),
        "Valid source/config matrix was rejected.");
    Json missing_configs = valid;
    missing_configs.erase("configs");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_matrix", missing_configs)),
        "Matrix accepted missing named configs.");
    require(dispatches == 1, "Invalid matrix arguments reached dispatch.");
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
        registry_exposes_canonical_load_workflows();
        empty_tool_schemas_declare_object_properties();
        atomic_action_schemas_reject_invalid_arguments();
        profile_recipe_requires_bounded_future_frames();
        matrix_schema_requires_named_sources_configs_and_axes();
        registry_declares_accurate_tool_annotations();
        std::cout << "PASS ActionSchemaRejectsForbiddenAndDuplicateTools\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaRejectsForbiddenAndDuplicateTools: " << error.what() << '\n';
        return 1;
    }
}
