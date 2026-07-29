#pragma once

#include "tool_registry.hpp"

#include <optional>
#include <string_view>

namespace vibris::mcp {

[[nodiscard]] std::optional<InvocationError> validate_argument_policy(
    std::string_view tool_name,
    const Json& arguments);

}