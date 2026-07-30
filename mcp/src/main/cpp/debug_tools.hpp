#pragma once

#include <string_view>

#include <nlohmann/json_fwd.hpp>

namespace vibris::mcp {

void append_debug_tool_definitions(nlohmann::json& definitions);
[[nodiscard]] bool is_debug_tool(std::string_view name) noexcept;

}
