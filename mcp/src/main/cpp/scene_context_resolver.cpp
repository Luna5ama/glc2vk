#include "scene_context_resolver.hpp"

#include "state_error.hpp"

#include <string>
#include <string_view>

namespace vibris::mcp {
namespace {

namespace proto = ::vibris::control::v1;

bool matches(const SessionConfig& config, const proto::SceneContext& context) {
    return context.save_id() == config.save_id && context.dimension_id() == config.dimension_id &&
        context.time_preset_id() == config.time_preset_id &&
        context.camera_preset_id() == config.camera_preset_id;
}

bool complete(const proto::SceneContext& context) {
    return !context.weather_preset_id().empty() && !context.settings_preset_id().empty() &&
        context.has_resolution() && context.resolution().width() != 0 && context.resolution().height() != 0;
}

[[noreturn]] void invalid(std::string_view message) {
    throw StateError("INVALID_PRESET", std::string(message), false);
}

}

proto::SceneContext SceneContextResolver::resolve(
    const SessionConfig& config, const proto::ListPresetsResponse& response) {
    const proto::SceneContext* match = nullptr;
    for (const auto& preset : response.presets()) {
        if (!matches(config, preset.context())) continue;
        if (match == nullptr) {
            match = &preset.context();
            continue;
        }
        const bool current_default = match->settings_preset_id() == "default";
        const bool candidate_default = preset.context().settings_preset_id() == "default";
        if (current_default != candidate_default) {
            if (candidate_default) match = &preset.context();
            continue;
        }
        invalid("The configured scene matches multiple server presets.");
    }
    if (match == nullptr) invalid("The configured scene does not match a server preset.");
    if (!complete(*match)) invalid("The matched server preset has an incomplete scene context.");
    return *match;
}

}