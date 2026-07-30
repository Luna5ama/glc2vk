#include "debug_tools.hpp"

#include <cstdint>
#include <initializer_list>
#include <limits>
#include <utility>

#include <nlohmann/json.hpp>

namespace vibris::mcp {
namespace {

using Json = nlohmann::json;

Json closed_object(Json properties, std::initializer_list<const char*> required = {}) {
    Json schema{{"type", "object"}, {"properties", std::move(properties)}, {"additionalProperties", false}};
    if (required.size() != 0) schema["required"] = required;
    return schema;
}

Json definition(const char* name, const char* description, Json schema, bool read_only) {
    return {{"name", name},
            {"description", description},
            {"inputSchema", std::move(schema)},
            {"annotations", {{"readOnlyHint", read_only}, {"destructiveHint", false}, {"openWorldHint", false}}}};
}

Json integer() {
    return {{"type", "integer"}, {"minimum", 0}, {"maximum", std::numeric_limits<std::int32_t>::max()}};
}

}

void append_debug_tool_definitions(Json& definitions) {
    const auto empty = closed_object({});
    const Json text{{"type", "string"}, {"minLength", 1}};
    const Json path{{"type", "string"}, {"minLength", 1}};
    const Json raw{{"type", "boolean"}, {"default", false}};
    const Json config{{"type", "object"}};
    const Json texture = {{"type", "object"},
                          {"oneOf",
                           {closed_object({{"name", text}, {"raw", raw}}, {"name"}),
                            closed_object({{"id", integer()}, {"raw", raw}}, {"id"})}}};

    definitions.push_back(definition("vibris_get_capture_status", "Read compute capture state.", empty, true));
    definitions.push_back(definition("vibris_reload_shader", "Reload the active shader and return shader errors.",
                                     closed_object({{"config", config}}), false));
    definitions.push_back(definition("vibris_capture_pass", "Queue one compute pass capture for the next frame.",
                                     closed_object({{"pass", text}, {"path", path}}, {"pass"}), false));
    definitions.push_back(definition("vibris_capture_multi", "Queue a prepare, begin, deferred, or composite capture.",
                                     closed_object({{"type", {{"type", "string"},
                                                                 {"enum", {"prepare", "begin", "deferred", "composite"}}}},
                                                    {"path", path}}, {"type"}), false));
    definitions.push_back(definition("vibris_get_shader_status", "Read the active shader-pack status.", empty, true));
    definitions.push_back(definition("vibris_get_shader_errors", "List captured shader errors.", empty, true));
    definitions.push_back(definition("vibris_schedule_screenshot", "Schedule a screenshot after rendered frames.",
                                     closed_object({{"frames", {{"type", "integer"}, {"minimum", 1},
                                                                  {"maximum", std::numeric_limits<std::int32_t>::max()},
                                                                  {"default", 1}}}}), false));
    definitions.push_back(definition("vibris_get_screenshot_result", "Read the last completed screenshot path.", empty, true));
    definitions.push_back(definition("vibris_get_gpu_metrics", "Measure GPU pass timings over the next rendered frames.",
                                     closed_object({{"frames", {{"type", "integer"}, {"minimum", 1},
                                                                  {"maximum", 10'000}}}},
                                                   {"frames"}), true));
    definitions.push_back(definition("vibris_list_ssbos", "List active shader storage buffers.", empty, true));
    definitions.push_back(definition("vibris_dump_ssbo", "Dump a shader storage buffer by binding index.",
                                     closed_object({{"index", integer()}}, {"index"}), false));
    definitions.push_back(definition("vibris_list_textures", "List active render and custom textures.", empty, true));
    definitions.push_back(definition("vibris_dump_texture", "Dump a texture by logical name or OpenGL id.", texture, false));
    definitions.push_back(definition("vibris_list_patched_shaders", "List patched shader debug files.", empty, true));
}

}
