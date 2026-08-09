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
    return one_of({
        closed_object({{"recipe", enum_string({"profile"})},
                       {"source", source_schema()},
                       {"config", config},
                       {"warmup_frames", frames},
                       {"frames", metric_frames},
                       {"result_detail", result_detail},
                       {"metric_filter", metric_filter},
                       {"statistics", statistics},
                       {"converted_units", converted_units},
                       {"max_retries", max_retries},
                       {"result_csv", {{"type", "boolean"}}}},
                      {"recipe", "frames"}),
        closed_object({{"recipe", enum_string({"profile_matrix"})},
                       {"sources", named_sources_schema()},
                       {"configs", named_configs_schema()},
                       {"matrix", matrix_axes_schema()},
                       {"warmup_frames", frames},
                       {"frames", metric_frames},
                       {"result_detail", result_detail},
                       {"metric_filter", metric_filter},
                       {"statistics", statistics},
                       {"converted_units", converted_units},
                       {"max_retries", max_retries},
                       {"execution", execution},
                       {"result_csv", {{"type", "boolean"}}}},
                      {"recipe", "sources", "configs", "matrix", "frames"}),
        closed_object({{"recipe", enum_string({"profile_matrix"})},
                       {"operation", enum_string({"status", "cancel"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}}},
                      {"recipe", "operation", "job_id"}),
        closed_object({{"recipe", enum_string({"profile_matrix"})},
                       {"operation", enum_string({"resume"})},
                       {"job_id", {{"type", "string"}, {"minLength", 1}}},
                       {"execution", execution}},
                      {"recipe", "operation", "job_id"}),
        closed_object({{"recipe", enum_string({"benchmark_ab"})},
                       {"baseline", source_schema()},
                       {"candidate", source_schema()},
                       {"config", config},
                       {"warmup_frames", frames},
                       {"frames", metric_frames},
                       {"rounds", bounded_integer(2, 20)},
                       {"control_rounds", bounded_integer(2, 20)},
                       {"order", enum_string({"abba", "abab", "randomized"})},
                       {"random_seed", bounded_integer(0, std::numeric_limits<std::uint32_t>::max())},
                       {"statistic", enum_string({"avg", "p5", "p50", "p95"})},
                       {"metric_filter", metric_filter},
                       {"max_retries", max_retries},
                       {"result_detail", result_detail}},
                      {"recipe", "baseline", "candidate", "frames"}),
        closed_object({{"recipe", enum_string({"load_and_screenshot"})},
                       {"source", source_schema()},
                       {"config", config},
                       {"warmup_frames", frames},
                       {"screenshot_format", enum_string({"png"})}},
                      {"recipe"}),
        closed_object({{"recipe", enum_string({"capture_debug_bundle"})},
                       {"source", source_schema()},
                       {"config", config},
                       {"warmup_frames", frames},
                       {"screenshot", {{"type", "boolean"}}},
                       {"textures", string_array(64)},
                       {"buffers", string_array(64)}},
                      {"recipe"}),
        closed_object({{"recipe", enum_string({"ab_compare"})},
                       {"a", source_variant_schema()},
                       {"b", source_variant_schema()},
                       {"config", config},
                       {"warmup_frames", frames},
                       {"captures", {{"type", "array"}, {"items", capture_schema()}, {"maxItems", 64}}}},
                      {"recipe", "a", "b", "captures"}),
    });
}

Json action_schema() {
    const Json artifact_name{{"type", "string"}, {"minLength", 1}};
    const Json resource_name{{"type", "string"}, {"minLength", 1}};
    const auto frames = bounded_integer(1, 10'000);
    const auto empty_action = [](const char* type) {
        return closed_object({{"type", enum_string({type})}}, {"type"});
    };
    auto load_shader = closed_object({{"type", enum_string({"load_shader"})},
                                      {"source", {{"type", "string"}, {"minLength", 1}}},
                                      {"config", {{"type", "string"}, {"minLength", 1}}}},
                                     {"type", "source", "config"});
    load_shader["description"] =
        "Closes any open screen, hides the HUD, loads the selected source and config, reloads the shader pipeline, "
        "applies the configured scene, resets temporal counters, and returns the resulting shader state, errors, "
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
                       {"name", resource_name},
                       {"format", enum_string({"bin", "png"})},
                       {"artifact_name", artifact_name}},
                      {"type", "name", "format", "artifact_name"}),
        closed_object({{"type", enum_string({"dump_buffer"})},
                       {"name", resource_name},
                       {"artifact_name", artifact_name}},
                      {"type", "name", "artifact_name"}),
        empty_action("get_capture_status"),
        load_shader,
        closed_object({{"type", enum_string({"capture_pass"})},
                       {"pass", resource_name}, {"path", resource_name}}, {"type", "pass"}),
        closed_object({{"type", enum_string({"capture_multi"})},
                       {"capture_type", enum_string({"prepare", "begin", "deferred", "composite"})},
                       {"path", resource_name}}, {"type", "capture_type"}),
        empty_action("inspect_shader"),
        closed_object({{"type", enum_string({"get_gpu_metrics"})}, {"frames", frames}}, {"type", "frames"}),
        empty_action("list_buffers"),
        empty_action("list_textures"),
        closed_object({{"type", enum_string({"get_patched_shaders"})},
                       {"artifact_name", artifact_name}}, {"type", "artifact_name"}),
    });
}

Json definition(const char* name, const char* description, Json input_schema, bool read_only) {
    return Json{{"name", name},
                {"description", description},
                {"inputSchema", std::move(input_schema)},
                {"annotations", {{"readOnlyHint", read_only}, {"destructiveHint", false}, {"openWorldHint", false}}}};
}

Json build_definitions() {
    const auto empty = closed_object(Json::object());
    const auto configure = closed_object(
        {{"save_id", {{"type", "string"}, {"minLength", 1}}},
         {"dimension_id", {{"type", "string"}, {"minLength", 1}}},
         {"time_preset_id", {{"type", "string"}, {"minLength", 1}}},
         {"camera_preset_id", {{"type", "string"}, {"minLength", 1}}},
         {"fov", {{"type", "number"}, {"minimum", 1}, {"maximum", 180}}},
         {"default_warmup_frames", bounded_integer(0, std::numeric_limits<std::uint32_t>::max())}},
        {"save_id", "dimension_id", "time_preset_id", "camera_preset_id", "fov", "default_warmup_frames"});
    Json definitions = Json::array({
        definition("vibris_get_config", "Read this MCP process's scene configuration and durable worktree ID.",
                   empty, true),
        definition("vibris_list_presets", "List valid Minecraft scene presets, optionally filtered by text.",
                   closed_object({{"filter", {{"type", "string"}, {"minLength", 1}}}}), true),
        definition("vibris_configure", "Validate and set this MCP process's scene configuration until it exits.",
                   configure, false),
        definition("vibris_get_status", "Read MCP, server, runtime, queue, resource, and artifact status.", empty,
                   true),
        definition("vibris_run_recipe",
                   "Run a standard shader workflow and return its terminal result. load_and_screenshot loads one "
                   "shader source and config, waits for the requested warmup frames, and saves a screenshot. Profile "
                   "recipes return normalized cases with summary, metrics, or full result detail. Profile matrices "
                   "support sync/async execution plus checkpoint status, resume, and cancellation operations. "
                   "benchmark_ab runs repeated paired ABBA, ABAB, or seeded randomized profiles plus same-commit "
                   "controls and returns guarded confidence and measured-noise comparisons.",
                   recipe_schema(), false),
        definition("vibris_run_actions",
                   "Run one ordered shader action sequence with explicitly named sources and configs.",
                   closed_object({{"sources", named_sources_schema()},
                                  {"configs", named_configs_schema()},
                                  {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                 {"actions"}), false),
        definition("vibris_run_matrix",
                   "Run the action template for every selected source and config combination. Each combination "
                   "automatically begins with load_shader; do not include load_shader in the action template.",
                   closed_object({{"sources", named_sources_schema()},
                                  {"configs", named_configs_schema()},
                                  {"matrix", matrix_axes_schema()},
                                  {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                 {"sources", "configs", "matrix", "actions"}), false),
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
    }
    if (value.is_string() && schema.contains("minLength") &&
        value.get_ref<const std::string&>().size() < schema["minLength"].get<std::size_t>()) {
        return path + " is too short";
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

Json mcp_result(Json payload, bool is_error) {
    const auto text = payload.dump();
    return Json{{"content", Json::array({{{"type", "text"}, {"text", text}}})},
                {"structuredContent", std::move(payload)},
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

    ToolOutcome outcome = dispatch_
                              ? dispatch_(name, arguments)
                              : ToolFailure{"SERVER_NOT_READY", "The MCP backend is not connected.", true,
                                            {{"tool", std::string(name)}}};
    if (const auto* value = std::get_if<Json>(&outcome)) return mcp_result(*value, false);

    const auto& failure = std::get<ToolFailure>(outcome);
    Json error{{"code", failure.code},
               {"message", failure.message},
               {"retryable", failure.retryable},
               {"details", failure.details}};
    return mcp_result({{"success", false}, {"error", std::move(error)}}, true);
}

} // namespace vibris::mcp