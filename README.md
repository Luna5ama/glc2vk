# vibris
OpenGL capture to Vulkan replay tool

## Hardware Requirements

A GPU with proper OpenGL 4.6 support

## Build Instructions

1. Clone this repo and [Luna5ama/gl-wrapper](https://github.com/Luna5ama/gl-wrapper) to the same directory
2. Build this project using Gradle

## Iris MCP integration

See [docs/capture-control.md](docs/capture-control.md) for the complete MCP build, packaging, server configuration,
shader profiling, capture recipes, artifacts, and troubleshooting guide.

For normal Codex use, track `.codex/config.toml` in each shader repository and point it at the packaged
`build\delivery\vibris-mcp.exe`. Leave both `cwd` and `--workspace-root` unset so each Codex task discovers its own Git
worktree from the task cwd; this supports concurrent tasks, linked worktrees, and independent shader repositories.
