#pragma once

#include <filesystem>

#include "tool_registry.hpp"

namespace vibris::mcp {

[[nodiscard]] Json analyze_nsight_bundle(
    const std::filesystem::path& workspace_root,
    const std::filesystem::path& artifact_path,
    const Json& query);

} // namespace vibris::mcp
