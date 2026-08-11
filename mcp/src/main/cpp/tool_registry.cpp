#include "tool_registry.hpp"

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <optional>
#include <string>
#include <utility>

#include "tool_argument_policy.hpp"

namespace vibris::mcp {
namespace {

Json closed_object(Json properties, std::initializer_list<const char*> required = {}) {
    Json schema{{"type", "object"}, {"properties", std::move(properties)}, {"additionalProperties", false}};
    if (required.size() != 0) {
        schema["required"] = required;
    }
    return schema;
}

Json enum_string(std::initializer_list<const char*> values) { return Json{{"type", "string"}, {"enum", values}}; }
Json bounded_integer(std::uint64_t minimum, std::uint64_t maximum) {
    return Json{{"type", "integer"}, {"minimum", minimum}, {"maximum", maximum}};
}
Json string_array(std::size_t maximum) {
    return Json{{"type", "array"}, {"items", {{"type", "string"}, {"minLength", 1}}}, {"maxItems", maximum}};
}
Json enum_array(std::initializer_list<const char*> values, std::size_t maximum) {
    return Json{{"type", "array"}, {"items", enum_string(values)}, {"maxItems", maximum}, {"uniqueItems", true}};
}
Json one_of(std::initializer_list<Json> schemas) { return Json{{"type", "object"}, {"oneOf", schemas}}; }
Json scoped(Json schema, bool scene_required) {
    schema["properties"]["worktree_root"] = {{"type", "string"}, {"minLength", 1}};
    if (!schema.contains("required")) schema["required"] = Json::array();
    schema["required"].push_back("worktree_root");
    if (scene_required) {
        schema["properties"]["preset_id"] = {{"type", "string"}, {"minLength", 1}};
        schema["required"].push_back("preset_id");
    }
    return schema;
}
Json source_schema() {
    return one_of({
        closed_object({{"kind", enum_string({"workspace"})}}, {"kind"}),
        closed_object({{"kind", enum_string({"commit"})}, {"revision", {{"type", "string"}, {"minLength", 1}}}},
                      {"kind", "revision"}),
    });
}
Json named_source_schema() {
    return one_of({
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"kind", enum_string({"workspace"})}}, {"id", "kind"}),
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"kind", enum_string({"commit"})},
                       {"revision", {{"type", "string"}, {"minLength", 1}}}},
                      {"id", "kind", "revision"}),
    });
}
Json named_config_schema() {
    return one_of({
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"values", {{"type", "object"}}}}, {"id", "values"}),
        closed_object({{"id", {{"type", "string"}, {"minLength", 1}}},
                       {"mode", enum_string({"preserve"})}}, {"id", "mode"}),
    });
}
Json named_sources_schema() {
    return Json{{"type", "array"}, {"items", named_source_schema()}, {"minItems", 1}, {"maxItems", 16}};
}
Json named_configs_schema() {
    return Json{{"type", "array"}, {"items", named_config_schema()}, {"minItems", 1}, {"maxItems", 64}};
}
Json matrix_axes_schema() {
    return closed_object({{"sources", string_array(16)}, {"configs", string_array(64)}},
                         {"sources", "configs"});
}
Json capture_schema() {
    return one_of({
        closed_object({{"type", enum_string({"screenshot"})},
                       {"format", enum_string({"png"})}},
                      {"type"}),
        closed_object({{"type", enum_string({"texture"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}},
                       {"format", enum_string({"bin", "png"})}},
                      {"type", "name"}),
        closed_object({{"type", enum_string({"buffer"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}}},
                      {"type", "name"}),
    });
}

Json visual_thresholds_schema() {
    const Json ratio{{"type", "number"}, {"minimum", 0.0}, {"maximum", 1.0}};
    const Json ssim{{"type", "number"}, {"minimum", -1.0}, {"maximum", 1.0}};
    return closed_object({
        {"pixel_error_threshold", ratio},
        {"max_mean_absolute_error", ratio},
        {"max_root_mean_square_error", ratio},
        {"max_p95_absolute_error", ratio},
        {"max_absolute_error", ratio},
        {"max_threshold_pixel_ratio", ratio},
        {"min_ssim", ssim},
    });
}

Json benchmark_visual_schema() {
    auto schema = visual_thresholds_schema();
    schema["properties"]["warmup_frames"] =
        bounded_integer(0, std::numeric_limits<std::uint32_t>::max());
    return schema;
}

Json source_variant_schema() {
    return closed_object({{"label", {{"type", "string"}, {"minLength", 1}}}, {"source", source_schema()}},
                         {"label", "source"});
}

Json recipe_schema() {
    const auto frames = bounded_integer(0, std::numeric_limits<std::uint32_t>::max());
    const auto metric_frames = bounded_integer(1, 10'000);
    const auto result_detail = enum_string({"summary", "metrics", "full"});
    const auto metric_filter = string_array(256);
    const auto statistics = enum_array({"avg", "p5", "p50", "p95"}, 4);
    const auto converted_units = enum_array({"us", "ms"}, 2);
    const auto max_retries = bounded_integer(0, 5);
    const auto execution = enum_string({"sync", "async"});
    const Json config{{"type", "object"}};
    return scoped(closed_object({
        {"recipe", enum_string({"profile", "profile_matrix", "benchmark_ab", "load_and_screenshot",
                                 "capture_debug_bundle", "ab_compare"})},
        {"source", source_schema()},
        {"baseline", source_schema()},
        {"candidate", source_schema()},
        {"a", source_variant_schema()},
        {"b", source_variant_schema()},
        {"sources", named_sources_schema()},
        {"configs", named_configs_schema()},
        {"matrix", matrix_axes_schema()},
        {"config", config},
        {"warmup_frames", frames},
        {"frames", metric_frames},
        {"rounds", bounded_integer(2, 20)},
        {"control_rounds", bounded_integer(2, 20)},
        {"order", enum_string({"abba", "abab", "randomized"})},
        {"random_seed", bounded_integer(0, std::numeric_limits<std::uint32_t>::max())},
        {"statistic", enum_string({"avg", "p5", "p50", "p95"})},
        {"result_detail", result_detail},
        {"metric_filter", metric_filter},
        {"statistics", statistics},
        {"converted_units", converted_units},
        {"max_retries", max_retries},
        {"execution", execution},
        {"result_csv", {{"type", "boolean"}}},
        {"screenshot_format", enum_string({"png"})},
        {"screenshot", {{"type", "boolean"}}},
        {"textures", string_array(64)},
        {"buffers", string_array(64)},
        {"captures", {{"type", "array"}, {"items", capture_schema()}, {"minItems", 1}, {"maxItems", 64}}},
        {"visual", benchmark_visual_schema()},
        {"visual_thresholds", visual_thresholds_schema()},
    }, {"recipe"}), true);
}

Json action_schema() {
    const Json artifact_name{{"type", "string"}, {"minLength", 1}};
    const Json resource_name{{"type", "string"}, {"minLength", 1}};
    const auto frames = bounded_integer(1, 10'000);
    const auto empty_action = [](const char* type) {
        return closed_object({{"type", enum_string({type})}}, {"type"});
    };
    auto load_shader = closed_object({{"type", enum_string({"load_shader"})},
                                      {"source_id", {{"type", "string"}, {"minLength", 1}}},
                                      {"config_id", {{"type", "string"}, {"minLength", 1}}}},
                                     {"type", "source_id", "config_id"});
    load_shader["description"] =
        "Closes any open screen, hides the HUD, loads the selected source and config, reloads the shader pipeline, "
        "applies the request-scoped scene, resets temporal counters, and returns the resulting shader state, errors, "
        "and structured reload diagnostics.";
    return one_of({
        closed_object({{"type", enum_string({"wait_frames"})},
                       {"frames", bounded_integer(1, std::numeric_limits<std::uint32_t>::max())}},
                      {"type", "frames"}),
        closed_object({{"type", enum_string({"take_screenshot"})},
                       {"format", enum_string({"png"})},
                       {"artifact_name", artifact_name},
                       {"after_frames", bounded_integer(0, std::numeric_limits<std::int32_t>::max())}},
                      {"type"}),
        closed_object({{"type", enum_string({"dump_texture"})},
                       {"resource", closed_object({{"logical_name", resource_name},
                                                   {"view", enum_string({"current", "alternate", "main", "alt"})},
                                                   {"mip_level", bounded_integer(0, 31)},
                                                   {"layer", bounded_integer(0, 4095)}},
                                                  {"logical_name"})},
                       {"format", enum_string({"bin", "png"})},
                       {"artifact_name", artifact_name}},
                      {"type", "resource", "format", "artifact_name"}),
        closed_object({{"type", enum_string({"dump_buffer"})},
                       {"logical_name", resource_name},
                       {"artifact_name", artifact_name}},
                      {"type", "logical_name", "artifact_name"}),
        empty_action("get_capture_status"),
        load_shader,
        closed_object({{"type", enum_string({"capture_pass"})},
                       {"pass_id", resource_name}, {"artifact_name", artifact_name}},
                      {"type", "pass_id", "artifact_name"}),
        closed_object({{"type", enum_string({"capture_multi"})},
                       {"capture_type", enum_string({"prepare", "begin", "deferred", "composite"})},
                       {"artifact_name", artifact_name}}, {"type", "capture_type", "artifact_name"}),
        empty_action("inspect_shader"),
        closed_object({{"type", enum_string({"get_gpu_metrics"})}, {"frames", frames},
                       {"metric_ids", string_array(256)}}, {"type", "frames"}),
        closed_object({{"type", enum_string({"list_resources"})},
                       {"kinds", enum_array({"final_framebuffer", "texture", "buffer", "patched_shaders"}, 4)},
                       {"logical_name", resource_name}, {"pass_id", resource_name}}, {"type"}),
        closed_object({{"type", enum_string({"get_patched_shaders"})},
                       {"artifact_name", artifact_name}}, {"type", "artifact_name"}),
    });
}

Json output_schema() {
    return closed_object({
        {"schema_version", {{"type", "integer"}, {"const", 2}}},
        {"success", {{"type", "boolean"}}},
        {"result", {{"type", "object"}}},
        {"error", closed_object({
            {"code", {{"type", "string"}, {"minLength", 1}}},
            {"message", {{"type", "string"}}},
            {"retryable", {{"type", "boolean"}}},
            {"details", {{"type", "object"}}},
        }, {"code", "message", "retryable", "details"})},
    }, {"schema_version", "success"});
}

Json definition(const char* name, const char* description, Json input_schema, bool read_only) {
    return Json{{"name", name},
                {"schema_version", 2},
                {"description", description},
                {"inputSchema", std::move(input_schema)},
                {"outputSchema", output_schema()},
                {"annotations", {{"readOnlyHint", read_only}, {"destructiveHint", false}, {"openWorldHint", false}}}};
}

Json build_definitions() {
    Json definitions = Json::array({
        definition("vibris_get_status",
                   "Read the compact v2 server, runtime, queue, lease and job status for the explicit Git worktree. "
                   "Resource catalogs are intentionally omitted.",
                   scoped(closed_object({
                       {"detail", enum_string({"summary", "jobs", "full"})},
                       {"wait_until", enum_string({"can_start_job", "job_terminal"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"timeout_ms", bounded_integer(0, 300'000)},
                   }), false), true),
        definition("vibris_list_presets",
                   "List Minecraft scene presets for the explicit Git worktree using exact preset and tag filters.",
                   scoped(closed_object({{"preset_id", {{"type", "string"}, {"minLength", 1}}},
                                         {"tags", string_array(32)}}), false), true),
        definition("vibris_list_resources",
                   "List the typed logical resource and named-pass catalog for the explicit Git worktree.",
                   scoped(closed_object({
                       {"kinds", enum_array({"final_framebuffer", "texture", "buffer", "patched_shaders"}, 4)},
                       {"logical_name", {{"type", "string"}, {"minLength", 1}}},
                       {"pass_id", {{"type", "string"}, {"minLength", 1}}},
                   }), false), true),
        definition("vibris_run_recipe",
                   "Run a standard shader workflow for the explicit Git worktree and scene preset. "
                   "load_and_screenshot loads one "
                   "shader source and config, waits for the requested warmup frames, and saves a screenshot. Profile "
                   "recipes return normalized cases with summary, metrics, or full result detail. Long-running "
                   "recipes support durable sync/async execution; query, result, resume and cancellation use vibris_job. "
                   "benchmark_ab runs repeated paired ABBA, ABAB, or seeded randomized profiles plus same-commit "
                   "controls and returns guarded confidence and measured-noise comparisons; optional visual "
                   "thresholds add a deterministic screenshot gate and a combined performance/visual verdict.",
                   recipe_schema(), false),
        definition("vibris_run_actions",
                   "Run one ordered shader action sequence synchronously or as a durable async job for the explicit "
                   "Git worktree and scene preset.",
                   scoped(closed_object({{"sources", named_sources_schema()},
                                         {"configs", named_configs_schema()},
                                         {"execution", enum_string({"sync", "async"})},
                                         {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                        {"actions"}), true), false),
        definition("vibris_run_matrix",
                   "Run the action template synchronously or as a durable async job for every selected source/config "
                   "combination in the explicit Git "
                   "worktree and scene preset. Each combination "
                   "automatically begins with load_shader; do not include load_shader in the action template.",
                   scoped(closed_object({{"sources", named_sources_schema()},
                                         {"configs", named_configs_schema()},
                                         {"matrix", matrix_axes_schema()},
                                         {"execution", enum_string({"sync", "async"})},
                                         {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                        {"sources", "configs", "matrix", "actions"}), true), false),
        definition("vibris_job",
                   "Query, retrieve, cancel or resume one durable v2 job for the explicit Git worktree.",
                   scoped(closed_object({
                       {"operation", enum_string({"query", "result", "cancel", "resume"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"event_cursor", bounded_integer(0, std::numeric_limits<std::uint64_t>::max())},
                       {"reason", {{"type", "string"}, {"maxLength", 512}}},
                   }, {"operation", "job_id"}), false), false),
        definition("vibris_artifacts",
                   "List, inspect, measure capacity for, or delete managed v2 artifact manifests in the explicit Git worktree.",
                   scoped(closed_object({
                       {"operation", enum_string({"list", "get", "capacity", "delete"})},
                       {"manifest_id", {{"type", "string"}, {"minLength", 1}}},
                       {"expected_manifest_sha256", {{"type", "string"}, {"minLength", 64}, {"maxLength", 64}}},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"request_id", {{"type", "string"}, {"minLength", 1}}},
                   }, {"operation"}), false), false),
    });
    return definitions;
}

bool has_type(const Json& value, std::string_view type) {
    if (type == "object") return value.is_object();
    if (type == "array") return value.is_array();
    if (type == "string") return value.is_string();
    if (type == "integer") return value.is_number_integer() || value.is_number_unsigned();
    if (type == "number") return value.is_number();
    if (type == "boolean") return value.is_boolean();
    return false;
}

std::optional<std::string> validate(const Json& value, const Json& schema, const std::string& path) {
    if (schema.contains("oneOf")) {
        const auto matches = std::count_if(schema["oneOf"].begin(), schema["oneOf"].end(), [&](const Json& candidate) {
            return !validate(value, candidate, path).has_value();
        });
        if (matches != 1) return path + " must match exactly one supported form";
    }
    if (schema.contains("type") && !has_type(value, schema["type"].get<std::string>())) {
        return path + " has the wrong type";
    }
    if (schema.contains("enum") &&
        std::find(schema["enum"].begin(), schema["enum"].end(), value) == schema["enum"].end()) {
        return path + " has an unsupported value";
    }
    if (value.is_object() && schema.contains("properties")) {
        for (const auto& required : schema.value("required", Json::array())) {
            if (!value.contains(required.get<std::string>())) {
                return path + "." + required.get<std::string>() + " is required";
            }
        }
        for (const auto& [key, item] : value.items()) {
            const auto property = schema["properties"].find(key);
            if (property == schema["properties"].end()) {
                if (!schema.value("additionalProperties", true)) return path + "." + key + " is not allowed";
            } else if (const auto error = validate(item, *property, path + "." + key)) {
                return error;
            }
        }
    }
    if (value.is_array() && schema.contains("items")) {
        if (schema.contains("minItems") && value.size() < schema["minItems"].get<std::size_t>()) {
            return path + " has too few items";
        }
        if (schema.contains("maxItems") && value.size() > schema["maxItems"].get<std::size_t>()) {
            return path + " has too many items";
        }
        for (std::size_t index = 0; index < value.size(); ++index) {
            if (const auto error = validate(value[index], schema["items"],
                                            path + "[" + std::to_string(index) + "]")) {
                return error;
            }
        }
        if (schema.value("uniqueItems", false)) {
            for (std::size_t left = 0; left < value.size(); ++left) {
                for (std::size_t right = left + 1; right < value.size(); ++right) {
                    if (value[left] == value[right]) return path + " must contain unique items";
                }
            }
        }
    }
    if (value.is_string() && schema.contains("minLength") &&
        value.get_ref<const std::string&>().size() < schema["minLength"].get<std::size_t>()) {
        return path + " is too short";
    }
    if (value.is_string() && schema.contains("maxLength") &&
        value.get_ref<const std::string&>().size() > schema["maxLength"].get<std::size_t>()) {
        return path + " is too long";
    }
    if (value.is_number() && schema.contains("minimum") &&
        value.get<double>() < schema["minimum"].get<double>()) {
        return path + " is too small";
    }
    if (value.is_number() && schema.contains("maximum") &&
        value.get<double>() > schema["maximum"].get<double>()) {
        return path + " is too large";
    }
    return std::nullopt;
}

bool physical_texture_alias(const std::string_view name) {
    return name.ends_with(".main") || name.ends_with(".alt");
}

std::optional<std::string> reject_physical_aliases(const Json& value, const std::string& path) {
    if (value.is_object()) {
        if (const auto name = value.find("logical_name"); name != value.end() && name->is_string() &&
            physical_texture_alias(name->get_ref<const std::string&>())) {
            return path + ".logical_name must use an explicit view instead of a physical suffix alias";
        }
        if (value.value("type", std::string{}) == "texture") {
            const auto name = value.find("name");
            if (name != value.end() && name->is_string() &&
                physical_texture_alias(name->get_ref<const std::string&>())) {
                return path + ".name must use a logical texture name instead of a physical suffix alias";
            }
        }
        for (const auto& [key, item] : value.items()) {
            if (key == "textures" && item.is_array()) {
                for (std::size_t index = 0; index < item.size(); ++index) {
                    if (item[index].is_string() &&
                        physical_texture_alias(item[index].get_ref<const std::string&>())) {
                        return path + ".textures[" + std::to_string(index) +
                            "] must use a logical texture name instead of a physical suffix alias";
                    }
                }
            }
            if (const auto error = reject_physical_aliases(item, path + "." + key)) return error;
        }
    } else if (value.is_array()) {
        for (std::size_t index = 0; index < value.size(); ++index) {
            if (const auto error = reject_physical_aliases(value[index], path + "[" + std::to_string(index) + "]")) {
                return error;
            }
        }
    }
    return std::nullopt;
}

std::optional<std::string> validate_operation_shape(const std::string_view name, const Json& arguments) {
    const auto require = [&](std::initializer_list<const char*> fields) -> std::optional<std::string> {
        for (const auto* field : fields) {
            if (!arguments.contains(field)) return "arguments." + std::string(field) + " is required";
        }
        return std::nullopt;
    };
    if (name == "vibris_run_recipe") {
        const auto recipe = arguments.at("recipe").get<std::string>();
        if (recipe == "profile") return require({"frames"});
        if (recipe == "profile_matrix") return require({"sources", "configs", "matrix", "frames"});
        if (recipe == "benchmark_ab") return require({"baseline", "candidate", "frames"});
        if (recipe == "ab_compare") return require({"a", "b", "captures"});
    }
    if (name == "vibris_artifacts") {
        const auto operation = arguments.at("operation").get<std::string>();
        if (operation == "get") return require({"manifest_id"});
        if (operation == "delete") return require({"manifest_id", "expected_manifest_sha256"});
    }
    return std::nullopt;
}

std::string bounded_summary(std::string value) {
    constexpr std::size_t maximum_bytes = 2048;
    if (value.size() > maximum_bytes) {
        std::size_t boundary = maximum_bytes;
        while (boundary != 0 && (static_cast<unsigned char>(value[boundary]) & 0xc0U) == 0x80U) --boundary;
        value.resize(boundary);
    }
    return value;
}

Json mcp_result(std::string_view tool_name, Json payload, bool is_error) {
    Json structured{{"schema_version", 2}, {"success", !is_error}};
    std::string summary;
    if (is_error) {
        summary = payload.value("code", std::string("ERROR")) + ": " +
            payload.value("message", std::string("Tool execution failed."));
        structured["error"] = std::move(payload);
    } else {
        summary = std::string(tool_name) + " completed successfully.";
        structured["result"] = std::move(payload);
    }
    return Json{{"content", Json::array({{{"type", "text"}, {"text", bounded_summary(std::move(summary))}}})},
                {"structuredContent", std::move(structured)},
                {"isError", is_error}};
}

} // namespace

ToolRegistry::ToolRegistry(ToolDispatch dispatch) : definitions_(build_definitions()), dispatch_(std::move(dispatch)) {}
const Json& ToolRegistry::definitions() const noexcept { return definitions_; }
InvocationResult ToolRegistry::invoke(std::string_view name, const Json& arguments) const {
    const auto definition_it = std::find_if(definitions_.begin(), definitions_.end(), [&](const Json& item) {
        return item["name"].get_ref<const std::string&>() == name;
    });
    if (definition_it == definitions_.end()) {
        return InvocationError{InvocationErrorCode::UnknownTool, "Unknown tool: " + std::string(name)};
    }
    if (const auto error = validate_argument_policy(name, arguments)) return *error;
    if (const auto error = validate(arguments, (*definition_it)["inputSchema"], "arguments")) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }
    if (const auto error = validate_operation_shape(name, arguments)) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }
    if (const auto error = reject_physical_aliases(arguments, "arguments")) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }

    ToolOutcome outcome = dispatch_
                              ? dispatch_(name, arguments)
                              : ToolFailure{"SERVER_NOT_READY", "The MCP backend is not connected.", true,
                                            {{"tool", std::string(name)}}};
    if (const auto* value = std::get_if<Json>(&outcome)) return mcp_result(name, *value, false);

    const auto& failure = std::get<ToolFailure>(outcome);
    Json error{{"code", failure.code},
               {"message", failure.message},
               {"retryable", failure.retryable},
               {"details", failure.details}};
    return mcp_result(name, std::move(error), true);
}

} // namespace vibris::mcp
