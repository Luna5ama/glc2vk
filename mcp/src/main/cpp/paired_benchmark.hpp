#pragma once

#include "tool_registry.hpp"

#include <cstddef>
#include <functional>
#include <string_view>

namespace vibris::mcp {

using PairedProfileExecutor = std::function<ToolOutcome(const Json&)>;
using PairedVisualExecutor = std::function<ToolOutcome(const Json&)>;

[[nodiscard]] Json visual_comparison_guards(const Json& result, bool require_heatmap);

[[nodiscard]] ToolOutcome run_paired_benchmark(
    const Json& arguments,
    std::string_view workflow_id,
    std::size_t default_warmup_frames,
    const PairedProfileExecutor& execute_profile,
    const PairedVisualExecutor& execute_visual = {});

} // namespace vibris::mcp
