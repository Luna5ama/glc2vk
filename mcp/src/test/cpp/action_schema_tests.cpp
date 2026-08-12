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

    auto recovery = scope;
    recovery["recipe"] = "recover_runtime";
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", recovery)),
        "source-free recovery recipe was rejected");
    recovery["preset_id"] = "scene";
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", recovery)),
        "recovery recipe accepted an unrelated scene preset");
    auto profile = scope;
    profile["recipe"] = "profile";
    profile["frames"] = 4;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", profile)),
        "scene recipe accepted a missing preset");
    auto benchmark = scope;
    benchmark["preset_id"] = "scene";
    benchmark["recipe"] = "benchmark_ab";
    benchmark["baseline"] = {{"kind", "workspace"}};
    benchmark["candidate"] = {{"kind", "workspace"}};
    benchmark["frames"] = 4;
    benchmark["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "always-restored benchmark accepted a disabled restore policy");
    benchmark.erase("restore_state");
    benchmark["metrics"] = Json::array({
        {{"metric_id", "target"}, {"role", "target"}},
        {{"metric_id", "sibling"}, {"role", "sibling"}, {"max_regression_ratio", 0.03}},
        {{"metric_id", "sentinel"}, {"role", "sentinel"}, {"max_regression_ratio", 0.01}},
    });
    benchmark["visual"] = {{"max_threshold_pixel_ratio", 0.001}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", benchmark)),
        "typed benchmark metrics with explicit guardrails were rejected");
    benchmark["visual"] = {{"warmup_frames", 4}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "benchmark accepted a visual gate without a deterministic threshold");
    benchmark["visual"] = {{"max_threshold_pixel_ratio", 0.001}};
    benchmark["metrics"][0]["max_regression_ratio"] = 0.0;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "target metric accepted a sibling/sentinel regression threshold");
    benchmark["metrics"].erase(benchmark["metrics"].begin());
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", benchmark)),
        "benchmark metrics without a target were accepted");
}

void typed_actions_reject_aliases() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json request{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"}};
    Json load = request;
    load["sources"] = Json::array({{{"id", "candidate"}, {"kind", "workspace"}}});
    load["configs"] = Json::array({{{"id", "quality"}, {"values", {{"QUALITY", 2}}}}});
    load["actions"] = Json::array({{{"type", "load_shader"},
        {"source_id", "candidate"}, {"config_id", "quality"}}});
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", load)),
        "typed source_id/config_id load action was rejected");
    load["actions"] = Json::array({{{"type", "load_shader"},
        {"source", "candidate"}, {"config", "quality"}}});
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", load)),
        "obsolete source/config load action was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_texture"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "alternate"}, {"mip_level", 1}, {"layer", 0}}},
         {"format", "bin"}, {"artifact_name", "color-bin"}},
        {{"type", "dump_buffer"}, {"resource", {{"logical_name", "sceneData"}}},
         {"artifact_name", "scene-data"}},
        {{"type", "dump_texture_after_pass"}, {"pass_id", "composite/composite21"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "current"}, {"mip_level", 0}, {"layer", 0}}},
         {"format", "png"}, {"artifact_name", "shade-diffuse"}},
        {{"type", "dump_buffer_after_pass"}, {"pass_id", "prepare/prepare3"},
         {"resource", {{"logical_name", "sceneData"}}}, {"artifact_name", "scene-data-after"}},
        {{"type", "list_resources"}, {"kinds", Json::array({"texture"})}},
    });
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "typed v2 resource actions were rejected");
    request["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", request)),
        "explicit transactional restore policy was rejected");
    request.erase("restore_state");

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

    request["actions"] = Json::array({
        {{"type", "dump_texture_after_pass"}, {"pass_id", "composite/composite21"},
         {"resource", {{"logical_name", "colortex0"}}},
         {"format", "png"}, {"artifact_name", "missing-view"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "texture selector without an explicit view was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_buffer_after_pass"}, {"pass_id", "prepare/prepare3"},
         {"resource", {{"logical_name", "sceneData"}, {"view", "current"}}},
         {"artifact_name", "buffer-view"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "buffer selector with a texture view was accepted");

    request["actions"] = Json::array({
        {{"type", "dump_texture_after_pass"}, {"pass_id", "composite21"},
         {"resource", {{"logical_name", "colortex0"}, {"view", "current"}}},
         {"format", "png"}, {"artifact_name", "fuzzy-pass"}},
    });
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", request)),
        "non-canonical after-pass identifier was accepted");

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

void compile_validation_shape_is_strict_and_typed() {
    const ToolRegistry registry([](std::string_view, const Json& arguments) { return arguments; });
    Json single{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "compile_validate"}, {"execution", "sync"},
        {"source", {{"kind", "workspace"}}}, {"config", {{"QUALITY", 2}}},
        {"baseline", {{"kind", "commit"}, {"revision", "HEAD~1"}}},
        {"baseline_config", {{"QUALITY", 1}}}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", single)),
        "typed single compile_validate recipe was rejected");

    Json matrix{{"worktree_root", "I:\\shader-worktree"}, {"preset_id", "scene"},
        {"recipe", "compile_validate"}, {"execution", "async"},
        {"sources", Json::array({{{"id", "candidate"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "quality"}, {"values", {{"QUALITY", 2}}}}})},
        {"matrix", {{"sources", Json::array({"candidate"})}, {"configs", Json::array({"quality"})}}}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", matrix)),
        "typed async compile validation matrix was rejected");

    auto invalid = single;
    invalid["restore_state"] = {{"on_success", false}, {"on_error", false}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate accepted caller-controlled restoration");
    invalid = single;
    invalid["frames"] = 4;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate accepted render sampling fields");
    invalid = matrix;
    invalid["source"] = {{"kind", "workspace"}};
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate mixed single and matrix source forms");
    invalid = single;
    invalid.erase("baseline");
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
        "compile_validate accepted baseline_config without a baseline source");
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
        compile_validation_shape_is_strict_and_typed();
        bounded_single_structured_result();
        std::cout << "PASS ActionSchemaV2ToolContract\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaV2ToolContract: " << error.what() << '\n';
        return 1;
    }
}
