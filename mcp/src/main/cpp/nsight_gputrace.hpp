#pragma once

#include "tool_registry.hpp"

namespace vibris::mcp {

// Launch Nsight Graphics GPU Trace on a game process via ngfx.exe and return
// the capture report. This is a pure native orchestration: it spawns ngfx
// with the requested profiler options, waits for the capture to finish (with
// a hard timeout safety net), and locates the newest .ngfx-gputrace report in
// the output directory. Game launch parameters (the executable and its full
// argument list) are supplied by the caller, so the tool stays independent of
// any specific Minecraft instance.
//
// Tool: vibris_gputrace_launch
[[nodiscard]] ToolOutcome launch_nsight_gputrace(const Json& arguments);

} // namespace vibris::mcp
