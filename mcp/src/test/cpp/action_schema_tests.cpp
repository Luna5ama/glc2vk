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

constexpr std::string_view worktree_root = "C:\\shader-worktree";

class TestRegistry final {
public:
    explicit TestRegistry(vibris::mcp::ToolDispatch dispatch = {}) : registry_(std::move(dispatch)) {}

    [[nodiscard]] const Json& definitions() const noexcept { return registry_.definitions(); }

    [[nodiscard]] vibris::mcp::InvocationResult invoke(std::string_view name, Json arguments) const {
        if (name == "vibris_list_presets" || name == "vibris_get_status" ||
            name == "vibris_run_recipe" || name == "vibris_run_actions" || name == "vibris_run_matrix") {
            arguments["worktree_root"] = worktree_root;
        }
        const bool control = name == "vibris_run_recipe" && arguments.contains("operation");
        if (!control && (name == "vibris_run_recipe" || name == "vibris_run_actions" ||
                            name == "vibris_run_matrix")) {
            arguments["preset_id"] = "scene-a";
        }
        return registry_.invoke(name, arguments);
    }

private:
    ToolRegistry registry_;
};

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

void schema_rejects_before_dispatch() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view, const Json&) {
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
    const Json flat{{"actions", Json::array({{{"type", "dump_texture"},
                                               {"name", "colortex5"},
                                               {"format", "bin"},
                                               {"artifact_name", "frame_1"}}})}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_actions", flat)),
        "Safe flat artifact name was rejected.");
    require(dispatches == 2, "Allowed action sequences were not dispatched exactly once each.");
}

void registry_has_exactly_the_supported_tools() {
    TestRegistry registry;
    std::set<std::string> names;
    for (const auto& definition : registry.definitions()) {
        names.insert(definition.at("name").get<std::string>());
    }
    const std::set<std::string> expected{
        "vibris_list_presets", "vibris_get_status",
        "vibris_run_recipe", "vibris_run_actions", "vibris_run_matrix",
        "vibris_gputrace_launch"};
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
    TestRegistry registry([&](std::string_view, const Json&) {
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
                    {{"type", "dump_buffer"}}})}})),
        "Buffer dump accepted missing required fields.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "get_patched_shaders"}}})}})),
        "Patched shader capture accepted a missing artifact name.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "list_patched_shaders"}}})}})),
        "Removed patched shader listing action remained exposed.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}, {"name", "colortex0"}, {"format", "raw"},
                     {"artifact_name", "texture"}}})}})),
        "Texture dump accepted the reserved raw format.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "capture_pass"}, {"pass", "prepare"}, {"path", "../outside"}}})}})),
        "Capture pass accepted a path that escapes the game directory.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}, {"name", "colortex0"}, {"format", "bin"},
                     {"artifact_name", "texture"}, {"raw", true}}})}})),
        "Texture dump accepted the removed raw selector.");
    require(std::holds_alternative<Json>(
                registry.invoke("vibris_run_actions", {{"actions", Json::array({
                    {{"type", "dump_texture"}, {"name", "colortex0.main"}, {"format", "bin"},
                     {"artifact_name", "texture"}},
                    {{"type", "dump_buffer"}, {"name", "iris_ssbo_6"},
                     {"artifact_name", "buffer"}}})}})),
        "Valid logical-name dumps were rejected.");
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
    TestRegistry registry([&](std::string_view, const Json&) {
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

void worktree_scoped_tools_require_explicit_context() {
    ToolRegistry registry;
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_list_presets", Json::object())),
        "Preset discovery accepted a missing worktree_root.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_get_status", Json::object())),
        "Status accepted a missing worktree_root.");
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_actions", {
        {"worktree_root", std::string(worktree_root)}, {"actions", Json::array()},
    })), "Action execution accepted a missing preset_id.");

    for (const auto& definition : registry.definitions()) {
        const auto& input_schema = definition.at("inputSchema");
        const auto& variant = input_schema.contains("oneOf") ? input_schema.at("oneOf").front() : input_schema;
        require(variant.at("properties").contains("worktree_root"),
            "A tool schema omitted worktree_root.");
    }
}

void preset_tools_use_typed_schema() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view name, const Json&) {
        ++dispatches;
        require(name == "vibris_run_recipe" || name == "vibris_list_presets",
            "Preset schema dispatched an unrelated tool.");
        return Json{{"accepted", true}};
    });

    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", {
        {"recipe", "load_and_screenshot"},
    })), "A recipe with request-scoped preset_id was rejected.");
    require(std::holds_alternative<Json>(registry.invoke("vibris_list_presets", {
        {"filter", ""}, {"filter_tags", Json::array({"sky", "regression"})},
    })), "Scene preset discovery rejected valid text and tag filters.");

    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_list_presets", {
        {"filter_tags", Json::array({1})},
    })), "Preset tag filtering accepted a non-string tag.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_configure", Json::object())),
        "Removed configure tool remained callable.");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_get_config", Json::object())),
        "Removed get_config tool remained callable.");
    require(dispatches == 2, "Invalid preset arguments reached dispatch.");
}

void profile_recipe_requires_bounded_future_frames() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view name, const Json& arguments) {
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

void profile_result_detail_schema() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view name, const Json&) {
        ++dispatches;
        require(name == "vibris_run_recipe", "Profile detail dispatched the wrong tool.");
        return Json{{"accepted", true}};
    });
    const Json profile{{"recipe", "profile"}, {"frames", 64}};
    const Json matrix{
        {"recipe", "profile_matrix"},
        {"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})},
        {"configs", Json::array({{{"id", "config"}, {"values", Json::object()}}})},
        {"matrix", {{"sources", Json::array({"source"})}, {"configs", Json::array({"config"})}}},
        {"frames", 64},
    };
    for (const auto* detail : {"summary", "metrics", "full"}) {
        auto profile_request = profile;
        profile_request["result_detail"] = detail;
        require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", profile_request)),
            "Profile rejected a supported result detail.");
        auto matrix_request = matrix;
        matrix_request["result_detail"] = detail;
        require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", matrix_request)),
            "Profile matrix rejected a supported result detail.");
    }
    auto filtered_profile = profile;
    filtered_profile["metric_filter"] = Json::array({"shadowcomp*", "composite18_total"});
    filtered_profile["statistics"] = Json::array({"avg", "p50"});
    filtered_profile["converted_units"] = Json::array({"us", "ms"});
    filtered_profile["result_csv"] = true;
    filtered_profile["max_retries"] = 5;
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", filtered_profile)),
        "Profile rejected valid metric filters or artifact options.");
    auto filtered_matrix = matrix;
    filtered_matrix["metric_filter"] = Json::array({"begin3_a"});
    filtered_matrix["statistics"] = Json::array({"p95"});
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", filtered_matrix)),
        "Profile matrix rejected valid metric filters.");
    auto asynchronous = matrix;
    asynchronous["execution"] = "async";
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", asynchronous)),
        "Profile matrix rejected asynchronous checkpoint execution.");
    for (const auto* operation : {"status", "cancel"}) {
        require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", {
            {"recipe", "profile_matrix"}, {"operation", operation},
            {"job_id", "11111111-2222-4333-8444-555555555555"},
        })), "Profile matrix rejected a checkpoint control operation.");
    }
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", {
        {"recipe", "profile_matrix"}, {"operation", "resume"},
        {"job_id", "11111111-2222-4333-8444-555555555555"}, {"execution", "sync"},
    })), "Profile matrix rejected checkpoint resume.");
    auto matrix_38 = matrix;
    matrix_38["configs"] = Json::array();
    matrix_38["matrix"]["configs"] = Json::array();
    for (std::size_t index = 0; index < 38; ++index) {
        const auto id = "config-" + std::to_string(index);
        matrix_38["configs"].push_back({{"id", id}, {"values", Json::object()}});
        matrix_38["matrix"]["configs"].push_back(id);
    }
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", matrix_38)),
        "Checkpointed profile matrix rejected the required 38-case workflow.");
    auto invalid_profile = profile;
    invalid_profile["result_detail"] = "verbose";
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_profile)),
        "Profile accepted an unsupported result detail.");
    auto invalid_matrix = matrix;
    invalid_matrix["result_detail"] = "verbose";
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_matrix)),
        "Profile matrix accepted an unsupported result detail.");
    auto invalid_statistic = profile;
    invalid_statistic["statistics"] = Json::array({"median"});
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_statistic)),
        "Profile accepted an unsupported statistic.");
    auto invalid_unit = profile;
    invalid_unit["converted_units"] = Json::array({"seconds"});
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_unit)),
        "Profile accepted an unsupported converted unit.");
    auto invalid_retries = profile;
    invalid_retries["max_retries"] = 6;
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_retries)),
        "Profile accepted an unbounded retry count.");
    auto invalid_execution = matrix;
    invalid_execution["execution"] = "detached";
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_execution)),
        "Profile matrix accepted an unsupported execution mode.");
    require(dispatches == 13, "Invalid profile output options reached dispatch.");
}

void paired_benchmark_recipe_schema() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view name, const Json& request) {
        ++dispatches;
        require(name == "vibris_run_recipe" && request.at("recipe") == "benchmark_ab",
            "Paired benchmark dispatched the wrong tool or recipe.");
        return Json{{"accepted", true}};
    });
    const Json valid{{"recipe", "benchmark_ab"},
                     {"baseline", {{"kind", "commit"}, {"revision", "HEAD~1"}}},
                     {"candidate", {{"kind", "workspace"}}},
                     {"config", {{"QUALITY", 2}}},
                     {"warmup_frames", 32},
                     {"frames", 120},
                     {"rounds", 5},
                     {"control_rounds", 3},
                     {"order", "randomized"},
                     {"random_seed", 42},
                     {"statistic", "p50"},
                     {"metric_filter", Json::array({"begin3_a", "composite_total"})},
                     {"max_retries", 2},
                     {"result_detail", "full"},
                     {"visual", {{"warmup_frames", 16}, {"pixel_error_threshold", 0.01},
                         {"max_mean_absolute_error", 0.002}, {"max_root_mean_square_error", 0.004},
                         {"max_p95_absolute_error", 0.01}, {"max_absolute_error", 0.1},
                         {"max_threshold_pixel_ratio", 0.001}, {"min_ssim", 0.995}}}};
    require(std::holds_alternative<Json>(registry.invoke("vibris_run_recipe", valid)),
        "Valid paired benchmark recipe was rejected.");

    for (const auto& [field, value] : std::array<std::pair<const char*, Json>, 5>{{
             {"rounds", 1}, {"control_rounds", 21}, {"order", "sequential"},
             {"statistic", "median"}, {"random_seed", -1},
         }}) {
        auto invalid = valid;
        invalid[field] = value;
        require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid)),
            "Paired benchmark accepted an invalid bounded or enumerated option.");
    }
    auto missing_baseline = valid;
    missing_baseline.erase("baseline");
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", missing_baseline)),
        "Paired benchmark accepted a missing baseline source.");
    auto invalid_visual_ratio = valid;
    invalid_visual_ratio["visual"]["max_threshold_pixel_ratio"] = 1.01;
    require(std::holds_alternative<InvocationError>(
                registry.invoke("vibris_run_recipe", invalid_visual_ratio)),
        "Paired benchmark accepted a visual ratio outside [0, 1].");
    auto invalid_ssim = valid;
    invalid_ssim["visual"]["min_ssim"] = -1.01;
    require(std::holds_alternative<InvocationError>(registry.invoke("vibris_run_recipe", invalid_ssim)),
        "Paired benchmark accepted an SSIM threshold outside [-1, 1].");
    require(dispatches == 1, "Invalid paired benchmark arguments reached dispatch.");
}

void matrix_schema_requires_named_sources_configs_and_axes() {
    std::size_t dispatches = 0;
    TestRegistry registry([&](std::string_view name, const Json&) {
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
    TestRegistry registry;
    const std::set<std::string> read_only{
        "vibris_list_presets", "vibris_get_status"};
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
        worktree_scoped_tools_require_explicit_context();
        preset_tools_use_typed_schema();
        atomic_action_schemas_reject_invalid_arguments();
        profile_recipe_requires_bounded_future_frames();
        profile_result_detail_schema();
        paired_benchmark_recipe_schema();
        matrix_schema_requires_named_sources_configs_and_axes();
        registry_declares_accurate_tool_annotations();
        std::cout << "PASS ActionSchemaRejectsForbiddenAndDuplicateTools\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL ActionSchemaRejectsForbiddenAndDuplicateTools: " << error.what() << '\n';
        return 1;
    }
}
