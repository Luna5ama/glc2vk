#pragma once

#include "tool_registry.hpp"

#include <cstddef>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace vibris::mcp {

using PairedProfileExecutor = std::function<ToolOutcome(const Json&)>;
using PairedVisualExecutor = std::function<ToolOutcome(const Json&)>;

struct PairedBenchmarkStep final {
	std::string phase;
	std::size_t round = 0;
	std::size_t slot = 0;
	std::string variant;
	std::string physical_source;
	std::string case_id;
};

[[nodiscard]] std::vector<PairedBenchmarkStep> paired_benchmark_plan(const Json& arguments);
[[nodiscard]] Json paired_benchmark_profile_arguments(
	const Json& arguments,
	const PairedBenchmarkStep& step,
	std::string_view workflow_id,
	std::size_t default_warmup_frames);
[[nodiscard]] Json paired_benchmark_visual_arguments(
	const Json& arguments, std::size_t default_warmup_frames);

[[nodiscard]] Json visual_comparison_guards(const Json& result, bool require_heatmap);

[[nodiscard]] ToolOutcome run_paired_benchmark(
    const Json& arguments,
    std::string_view workflow_id,
    std::size_t default_warmup_frames,
    const PairedProfileExecutor& execute_profile,
    const PairedVisualExecutor& execute_visual = {});

} // namespace vibris::mcp
