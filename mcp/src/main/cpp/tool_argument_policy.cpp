#include "tool_argument_policy.hpp"

#include "config_store.hpp"

#include <algorithm>
#include <cstddef>
#include <string>

namespace vibris::mcp {
namespace {

bool safe_flat_name(std::string_view value) {
    if (value.empty() || value.size() > 128 || value == "." || value == "..") return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-';
    });
}

}

std::optional<InvocationError> validate_argument_policy(std::string_view tool_name, const Json& arguments) {
    if (tool_name == "vibris_configure" && arguments.dump().size() > kMaxConfigJsonBytes) {
        return InvocationError{InvocationErrorCode::InvalidArguments, "Config JSON exceeds the 64 KiB limit.",
                               {{"code", "REQUEST_TOO_LARGE"}, {"retryable", false}}};
    }
    if (tool_name != "vibris_run_actions" || !arguments.is_object()) return std::nullopt;
    const auto actions = arguments.find("actions");
    if (actions == arguments.end() || !actions->is_array()) return std::nullopt;
    for (std::size_t index = 0; index < actions->size(); ++index) {
        if (!(*actions)[index].is_object()) continue;
        const auto name = (*actions)[index].find("artifact_name");
        if (name != (*actions)[index].end() && name->is_string() &&
            !safe_flat_name(name->get_ref<const std::string&>())) {
            return InvocationError{InvocationErrorCode::InvalidArguments,
                "arguments.actions[" + std::to_string(index) + "].artifact_name must be a safe flat name"};
        }
    }
    return std::nullopt;
}

}