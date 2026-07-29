#include "tool_registry.hpp"

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <optional>
#include <string>
#include <utility>

#include "config_store.hpp"
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
Json one_of(std::initializer_list<Json> schemas) { return Json{{"type", "object"}, {"oneOf", schemas}}; }
Json source_schema() {
    return one_of({
        closed_object({{"kind", enum_string({"workspace"})}}, {"kind"}),
        closed_object({{"kind", enum_string({"commit"})}, {"revision", {{"type", "string"}, {"minLength", 1}}}},
                      {"kind", "revision"}),
    });
}
Json capture_schema() {
    return one_of({
        closed_object({{"type", enum_string({"screenshot"})},
                       {"format", enum_string({"png"})}},
                      {"type"}),
        closed_object({{"type", enum_string({"texture"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}},
                       {"format", enum_string({"raw", "png"})}},
                      {"type", "name"}),
        closed_object({{"type", enum_string({"buffer"})},
                       {"name", {{"type", "string"}, {"minLength", 1}}},
                       {"format", enum_string({"bin"})}},
                      {"type", "name"}),
    });
}

Json source_variant_schema() {
    return closed_object({{"label", {{"type", "string"}, {"minLength", 1}}}, {"source", source_schema()}},
                         {"label", "source"});
}

Json recipe_schema() {
    const auto frames = bounded_integer(0, std::numeric_limits<std::uint32_t>::max());
    return one_of({
        closed_object({{"recipe", enum_string({"reload_and_capture"})},
                       {"source", source_schema()},
                       {"warmup_frames", frames},
                       {"screenshot_format", enum_string({"png"})}},
                      {"recipe"}),
        closed_object({{"recipe", enum_string({"capture_debug_bundle"})},
                       {"source", source_schema()},
                       {"warmup_frames", frames},
                       {"screenshot", {{"type", "boolean"}}},
                       {"textures", string_array(64)},
                       {"buffers", string_array(64)}},
                      {"recipe"}),
        closed_object({{"recipe", enum_string({"ab_compare"})},
                       {"a", source_variant_schema()},
                       {"b", source_variant_schema()},
                       {"warmup_frames", frames},
                       {"captures", {{"type", "array"}, {"items", capture_schema()}, {"maxItems", 64}}}},
                      {"recipe", "a", "b", "captures"}),
    });
}

Json action_schema() {
    const Json artifact_name{{"type", "string"}, {"minLength", 1}};
    const Json resource_name{{"type", "string"}, {"minLength", 1}};
    return one_of({
        closed_object({{"type", enum_string({"reset_temporal_state"})}}, {"type"}),
        closed_object({{"type", enum_string({"wait_frames"})},
                       {"frames", bounded_integer(1, std::numeric_limits<std::uint32_t>::max())}},
                      {"type", "frames"}),
        closed_object({{"type", enum_string({"capture_screenshot"})},
                       {"format", enum_string({"png"})},
                       {"artifact_name", artifact_name}},
                      {"type"}),
        closed_object({{"type", enum_string({"dump_texture"})},
                       {"name", resource_name},
                       {"format", enum_string({"raw", "png"})},
                       {"artifact_name", artifact_name}},
                      {"type", "name", "format", "artifact_name"}),
        closed_object({{"type", enum_string({"dump_buffer"})},
                       {"name", resource_name},
                       {"format", enum_string({"bin"})},
                       {"artifact_name", artifact_name}},
                      {"type", "name", "format", "artifact_name"}),
    });
}

Json definition(const char* name, const char* description, Json input_schema) {
    return Json{{"name", name}, {"description", description}, {"inputSchema", std::move(input_schema)}};
}

Json build_definitions() {
    const auto empty = closed_object({});
    const auto configure = closed_object(
        {{"save_id", {{"type", "string"}, {"minLength", 1}}},
         {"dimension_id", {{"type", "string"}, {"minLength", 1}}},
         {"time_preset_id", {{"type", "string"}, {"minLength", 1}}},
         {"camera_preset_id", {{"type", "string"}, {"minLength", 1}}},
         {"fov", {{"type", "number"}, {"minimum", 1}, {"maximum", 180}}},
         {"default_warmup_frames", bounded_integer(0, std::numeric_limits<std::uint32_t>::max())}},
        {"save_id", "dimension_id", "time_preset_id", "camera_preset_id", "fov", "default_warmup_frames"});
    return Json::array({
        definition("vibris_get_config", "Read this worktree's persisted Vibris configuration.", empty),
        definition("vibris_list_presets", "List valid Minecraft scene presets, optionally filtered by text.",
                   closed_object({{"filter", {{"type", "string"}, {"minLength", 1}}}})),
        definition("vibris_configure", "Validate and persist this worktree's Vibris scene configuration.", configure),
        definition("vibris_get_status", "Read MCP, server, runtime, queue, resource, and artifact status.", empty),
        definition("vibris_run_recipe",
                   "Prefer this tool for standard shader tests. The MCP prepares immutable source data, submits one "
                   "non-interruptible job, and waits synchronously for the final result. Use vibris_run_actions only "
                   "when no existing recipe can express the request.",
                   recipe_schema()),
        definition("vibris_run_actions",
                   "Advanced escape hatch for custom wait, capture, and dump sequences that recipes cannot express. "
                   "Source and context activation remain system-managed, and all actions run as one non-interruptible "
                   "job.",
                   closed_object({{"source", source_schema()},
                                  {"actions", {{"type", "array"}, {"items", action_schema()}, {"maxItems", 64}}}},
                                 {"actions"})),
    });
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
    if (name == "vibris_configure" && arguments.dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, "Config JSON exceeds the 64 KiB limit.",
                               {{"code", "REQUEST_TOO_LARGE"}, {"retryable", false}}};
    }
    if (const auto error = validate(arguments, (*definition_it)["inputSchema"], "arguments")) {
        return InvocationError{InvocationErrorCode::InvalidArguments, *error};
    }

    ToolOutcome outcome = dispatch_
                              ? dispatch_(name, arguments)
                              : ToolFailure{"SERVER_NOT_READY", "The Phase 1 tool backend is not connected.", true,
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