#include "tool_registry.hpp"

#include <algorithm>
#include <array>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>

namespace {

using vibris::mcp::InvocationError;
using vibris::mcp::Json;
using vibris::mcp::ToolRegistry;

void require(const bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

const Json& definition(const ToolRegistry& registry, const std::string_view name) {
    const auto found = std::find_if(registry.definitions().begin(), registry.definitions().end(),
        [name](const Json& value) { return value.at("name").get<std::string>() == name; });
    if (found == registry.definitions().end()) throw std::runtime_error("missing tool definition");
    return *found;
}

void verify_typed_schema(const Json& schema, const std::string& path) {
    require(schema.is_object(), path + " is not an object schema");
    if (schema.contains("oneOf")) {
        require(schema.at("oneOf").is_array() && !schema.at("oneOf").empty(), path + " has an empty oneOf");
        for (std::size_t index = 0; index < schema.at("oneOf").size(); ++index) {
            verify_typed_schema(schema.at("oneOf")[index], path + ".oneOf[" + std::to_string(index) + "]");
        }
        return;
    }
    require(schema.contains("type") && schema.at("type").is_string(), path + " has no concrete type");
    if (schema.value("type", std::string{}) == "object" && schema.contains("properties")) {
        require(!schema.at("properties").contains("args"), path + " exposes args: unknown");
        for (const auto& [name, property] : schema.at("properties").items()) {
            verify_typed_schema(property, path + ".properties." + name);
        }
    }
    if (schema.value("type", std::string{}) == "array") {
        require(schema.contains("items"), path + " has untyped array items");
        verify_typed_schema(schema.at("items"), path + ".items");
    }
}

void exact_v2_tool_catalog() {
    const ToolRegistry registry;
    const std::array expected{
        "vibris_get_status",
        "vibris_list_presets",
        "vibris_list_resources",
        "vibris_run_recipe",
        "vibris_run_actions",
        "vibris_run_matrix",
        "vibris_job",
        "vibris_artifacts",
    };
    require(registry.definitions().size() == expected.size(), "tools/list must expose exactly eight tools");
    std::unordered_set<std::string> names;
    for (const auto& tool : registry.definitions()) {
        const auto name = tool.at("name").get<std::string>();
        names.insert(name);
        require(tool.at("schema_version") == 2, "tool metadata omitted schema_version 2");
        require(tool.contains("inputSchema") && tool.contains("outputSchema"), "tool omitted a schema");
        require(!tool.at("inputSchema").contains("oneOf"), "tool has a forbidden top-level oneOf");
        const auto& required = tool.at("inputSchema").at("required");
        require(std::find(required.begin(), required.end(), "worktree_root") != required.end(),
            "tool omitted required worktree_root");
        verify_typed_schema(tool.at("inputSchema"), name + ".inputSchema");
        verify_typed_schema(tool.at("outputSchema"), name + ".outputSchema");
    }
    require(names == std::unordered_set<std::string>(expected.begin(), expected.end()),
        "tools/list exposed the wrong v2 names");
}

void exact_filters_and_job_control() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    const Json scope{{"worktree_root", "I:\\shader-worktree"}};

    auto presets = scope;
    presets["preset_id"] = "sky-noon-1";
    presets["tags"] = Json::array({"sky"});
    require(std::holds_alternative<Json>(registry.invoke("vibris_list_presets", presets)),
        "exact preset filters were rejected");
    presets.erase("preset_id");
    presets["filter"] = "sky";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_list_presets", presets)),
        "legacy fuzzy preset filter was accepted");

    auto resources = scope;
    resources["kinds"] = Json::array({"texture", "buffer"});
    resources["logical_name"] = "colortex0";
    resources["pass_id"] = "composite/composite1";
    require(std::holds_alternative<Json>(registry.invoke("vibris_list_resources", resources)),
        "exact resource filters were rejected");
    resources["logical_name"] = "colortex0.main";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_list_resources", resources)),
        "physical texture suffix alias was accepted");

    auto job = scope;
    job["operation"] = "query";
    job["job_id"] = "job-1";
    require(std::holds_alternative<Json>(registry.invoke("vibris_job", job)), "typed job query was rejected");
    auto old_control = scope;
    old_control["recipe"] = "profile_matrix";
    old_control["operation"] = "status";
    old_control["job_id"] = "job-1";
    old_control["preset_id"] = "scene";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", old_control)),
        "legacy recipe control operation was accepted");
}

void typed_actions_reject_aliases() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json request{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"}};
    request["actions"] = Json::array({
        {{"type", "dump_texture"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "alternate"}, {"mip_level", 1}, {"layer", 0}}},
         {"format", "bin"}, {"artifact_name", "color-bin"}},
        {{"type", "dump_buffer"}, {"logical_name", "sceneData"}, {"artifact_name", "scene-data"}},
        {{"type", "list_resources"}, {"kinds", Json::array({"texture"})}},
    });
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "typed v2 resource actions were rejected");

    for (const auto* old_type : {"list_textures", "list_buffers", "dump_texture_v2"}) {
        request["actions"] = Json::array({{{"type", old_type}}});
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
            "legacy action alias was accepted");
    }
    request["actions"] = Json::array({
        {{"type", "dump_texture"},
         {"resource", {{"logical_name", "colortex0.alt"}, {"view", "current"}}},
         {"format", "png"}, {"artifact_name", "bad-alias"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "physical texture suffix alias was accepted in an action");

    Json debug_bundle{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "capture_debug_bundle"}, {"textures", Json::array({"colortex0.main"})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", debug_bundle)),
        "physical texture suffix alias was accepted in a debug-bundle recipe");

    Json ab_compare{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "ab_compare"},
        {"a", {{"label", "baseline"}, {"source", {{"kind", "workspace"}}}}},
        {"b", {{"label", "candidate"}, {"source", {{"kind", "workspace"}}}}},
        {"captures", Json::array({{{"type", "texture"}, {"name", "colortex0.alt"}}})}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", ab_compare)),
        "physical texture suffix alias was accepted in an A/B capture recipe");
}

void bounded_single_structured_result() {
    constexpr std::string_view marker = "FULL_PAYLOAD_MARKER";
    const ToolRegistry registry([](std::string_view, const Json&) {
        return Json{{"marker", marker}, {"large", std::string(16 * 1024, 'x')}};
    });
    const auto invocation = registry.invoke("vibris_get_status", {{"worktree_root", "I:\\shader-worktree"}});
    require(std::holds_alternative<Json>(invocation), "representative result invocation failed");
    const auto& result = std::get<Json>(invocation);
    require(result.at("content").size() == 1, "result must contain one bounded text summary");
    const auto summary = result.at("content").front().at("text").get<std::string>();
    require(summary.size() <= 2048 && summary.find(marker) == std::string::npos,
        "text content duplicated or exceeded the structured payload");
    const auto& structured = result.at("structuredContent");
    require(structured.at("schema_version") == 2 && structured.at("success") == true,
        "structured result omitted its v2 envelope");
    require(structured.at("result").at("marker").get<std::string>() == marker,
        "full result was not present exactly in structuredContent");
    require(result.dump().find(std::string(marker)) == result.dump().rfind(std::string(marker)),
        "full payload marker was duplicated outside structuredContent");
}

} // namespace

int main() {
    try {
        exact_v2_tool_catalog();
        exact_filters_and_job_control();
        typed_actions_reject_aliases();
        bounded_single_structured_result();
        std::cout << "PASS ActionSchemaV2ToolContract\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaV2ToolContract: " << error.what() << '\n';
        return 1;
    }
}