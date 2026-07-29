# Vibris shader control

Vibris exposes one native MCP executable for shader testing. `vibris-mcp.exe` speaks newline-delimited MCP JSON-RPC
on stdin/stdout and connects to the Vibris server embedded in Iris over loopback gRPC. Iris owns Minecraft, shader
reloads, render-thread work, and capture resources; the MCP owns worktree configuration, immutable source preparation,
the worktree lock, and synchronous tool results.

```text
Codex or another MCP client
        | stdio MCP
        v
vibris-mcp.exe --workspace-root <git-worktree>
        | protobuf over loopback gRPC
        v
patched Iris JAR -> Vibris core -> render-thread host adapter
        |                         |
        v                         v
vibris/pending/<source UUID>      vibris/artifacts/<workspace UUID>/<request UUID>
```

There is one MCP process per Git worktree and one shared Iris process. A named mutex prevents two MCP processes from
owning the same worktree. Jobs from multiple worktrees are scheduled fairly, but each job is non-interruptible and only
one job touches Minecraft at a time.

## Build

Requirements are Java 21, CMake 3.25+, Visual Studio 2022, vcpkg, and the sibling `Iris` checkout. Set `VCPKG_ROOT`
before configuring the native build.

All handwritten JVM production implementations are Kotlin. The native MCP remains C++; Java emitted by protobuf is
generated under `build` and is not handwritten production source.

```powershell
# Java libraries and protocol classes
I:\code\vibris\gradlew.bat -p I:\code\vibris build

# Native MCP
Set-Location I:\code\vibris\mcp
cmake --preset windows-vs2022
cmake --build --preset release
ctest --preset release

# Patched Iris distribution; settings.gradle.kts includes ../vibris as a composite build
Set-Location I:\code\Iris
.\gradlew.bat :fabric:remapJar
```

The release MCP is `mcp/out/build/Release/vibris-mcp.exe`. The patched Iris artifact is the single
`Iris/build/libs/iris-fabric-*-local.jar`.

For the native lifetime gate, install Clang with its Windows sanitizer runtime and run:

```powershell
Set-Location I:\code\vibris\mcp
cmake --preset windows-clang-sanitizers
cmake --build --preset sanitizers
ctest --preset sanitizers
```

The sanitizer preset runs `StdioLifecycle`, `GrpcReconnect`, and `SourceSoak`. Sanitizer diagnostics make the test fail;
the MCP also records allocator metrics when `VIBRIS_SOAK_METRICS` names a JSONL output file.

On Windows, the sanitizer tests and soak set `ASAN_OPTIONS=detect_container_overflow=0` because the vcpkg static
dependencies are not ASan-instrumented and therefore cannot maintain Protobuf's cross-module container annotations.
AddressSanitizer address checks, UndefinedBehaviorSanitizer, and the live allocator counters remain enabled.

## Package and launch

A delivery contains exactly two top-level files:

- `vibris-mcp.exe`
- one patched Iris JAR

The Iris JAR embeds exactly one Vibris API, core, and protocol JAR plus the required gRPC runtime JARs. Do not ship a
second Vibris mod JAR. `source-package-audit.ps1` verifies this layout and the corresponding source boundaries.

Before starting Iris, create `<gameDir>/config/vibris/server.json` and every configured directory. V1 never falls back
from a missing or read-only RAM-disk root to an SSD path.

```json
{
  "schema_version": 1,
  "listen_address": "127.0.0.1:50051",
  "pending_shaders_root": "R:\\shaders",
  "artifact_root": "R:\\vibris\\artifacts",
  "artifact_quota_bytes": 3221225472,
  "shaderpack_root": ".minecraft\\shaderpacks\\vibris",
  "max_source_bytes": 536870912,
  "max_source_files": 100000,
  "max_global_queue": 32,
  "max_actions_per_job": 64
}
```

Only `127.0.0.1` listen addresses are accepted. Relative paths are resolved from the game directory; the documented
`.minecraft` prefix is removed when the game directory itself is named `.minecraft`. If the file is missing or invalid,
or any configured directory is missing or not writable, the loopback listener still starts in `NOT_READY` state.
`vibris_get_status` then returns `SERVER_NOT_READY` with the explicit startup reason.

Start the packaged Iris client first, then configure the MCP client with:

```text
I:\code\vibris\mcp\out\build\Release\vibris-mcp.exe
    --workspace-root I:\code\shaderpack-worktree
    --server-address 127.0.0.1:50051
```

For Codex, add the native executable to `config.toml`; TOML literal strings keep Windows paths readable:

```toml
[mcp_servers.vibris]
command = 'I:\code\vibris\build\delivery\vibris-mcp.exe'
args = [
  '--workspace-root',
  'I:\code\shaderpack-worktree',
  '--server-address',
  '127.0.0.1:50051',
]
```

`--workspace-root` must be a real Git worktree containing a `shaders` directory. If it is omitted, the MCP searches
upward from its current directory. The server address must be the loopback endpoint published by the running Iris
instance; `127.0.0.1:50051` is the default.

On Windows, packaged-client integration probes attach a window guard to the owned Iris runtime. It minimizes that
runtime's visible windows without activating them and fails the probe if Iris reaches the foreground, so automated
packaged-client runs do not steal focus from the desktop.

Closing MCP stdin performs an orderly shutdown: pending calls are resolved, the gRPC completion queue closes, its worker
thread joins, prepared sources still owned by the MCP are removed, and the worktree mutex is released.

## Worktree configuration

`vibris_configure` validates a scene against the live Iris preset catalog and atomically writes
`.codex/vibris-session.json` in the worktree. The managed schema is:

```json
{
  "schema_version": 1,
  "workspace_id": "c58a84bf-f6ee-4d53-9b31-7af03dfaf500",
  "shader_directory": "shaders",
  "save_id": "vibris-phase4-world",
  "dimension_id": "minecraft:overworld",
  "time_preset_id": "sunset",
  "camera_preset_id": "rooftop",
  "fov": 70.0,
  "default_warmup_frames": 32
}
```

The MCP creates `workspace_id`, fixes `shader_directory` to `shaders`, and preserves both fields on later configuration
updates. The user-facing configure call supplies only the remaining six scene fields. Configuration is isolated per
worktree and survives MCP restarts.

Iris reads its preset catalog from `<gameDir>/config/vibris/presets.json`. Use `vibris_list_presets` before configuring
when save, dimension, time, or camera identifiers are not known.

## Six MCP tools

The server negotiates MCP protocol `2024-11-05` and advertises tools only. Every tool result contains one JSON text
content item and matching structured content.

| Tool | Arguments | Result |
|------|-----------|--------|
| `vibris_get_config` | empty object | configured flag, worktree root, workspace ID, persisted config |
| `vibris_list_presets` | optional non-empty `filter` | matching live preset catalog entries |
| `vibris_configure` | save, dimension, time, camera, FOV, default warmup frames | validated persisted config |
| `vibris_get_status` | empty object | server/runtime state, queue, resources, pending/artifact roots and quota |
| `vibris_run_recipe` | one recipe form below | synchronous terminal job result and artifact metadata |
| `vibris_run_actions` | optional source plus up to 64 actions | synchronous terminal job result and artifact metadata |

There are no MCP resources and no aliases for these tool names. Unknown methods use JSON-RPC `-32601`; invalid tool
arguments use a structured tool error rather than expanding the tool surface.

## Sources

Recipe and action jobs accept either source form:

```json
{"kind":"workspace"}
{"kind":"commit","revision":"HEAD~1"}
```

`workspace` snapshots the current worktree `shaders` directory, including uncommitted shader edits. `commit` resolves a
Git revision and extracts only its `shaders` tree. Source trees reject reparse points and non-ordinary entries and are
bounded by the file/byte limits advertised by Iris.

The MCP writes each immutable snapshot to `<gameDir>/vibris/pending/<UUID>`. gRPC carries only the prepared source UUID,
origin, file count, and total byte count. Iris re-inspects and owns that directory before accepting the job; source file
contents are never embedded in protobuf messages. The active source is retained until the next activation, while stale
or rejected sources are removed.

## Recipes

Prefer `vibris_run_recipe` when one of the three standard jobs fits.

### Reload and capture

```json
{
  "recipe": "reload_and_capture",
  "source": {"kind": "workspace"},
  "warmup_frames": 32,
  "screenshot_format": "png"
}
```

Activates the source, applies the configured context, reloads shaders, waits the requested frames, and captures one
final screenshot.

### Capture a debug bundle

```json
{
  "recipe": "capture_debug_bundle",
  "source": {"kind": "workspace"},
  "warmup_frames": 32,
  "screenshot": true,
  "textures": ["colortex0", "depthtex0"],
  "buffers": ["radiance_cache"]
}
```

Captures the requested screenshot, textures, and buffers from one frame. Texture and buffer names must be present in the
resource catalog returned by `vibris_get_status`.

### A/B comparison

```json
{
  "recipe": "ab_compare",
  "a": {"label": "baseline", "source": {"kind": "commit", "revision": "HEAD"}},
  "b": {"label": "candidate", "source": {"kind": "workspace"}},
  "warmup_frames": 32,
  "captures": [
    {"type": "screenshot", "format": "png"},
    {"type": "texture", "name": "colortex0", "format": "raw"}
  ]
}
```

Runs both variants under the same configured scene and capture specification. Capture targets are screenshot PNG,
texture raw/PNG, and buffer BIN. The result includes comparison metrics and the two sets of artifacts.

## Custom actions

Use `vibris_run_actions` only when a recipe cannot express the sequence. Supported actions are:

- `reset_temporal_state`
- `wait_frames` with `frames >= 1`
- `capture_screenshot` with optional PNG format and artifact name
- `dump_texture` with logical resource name, raw/PNG format, and artifact name
- `dump_buffer` with logical resource name, BIN format, and artifact name

The source and configured scene are activated by the system, not by user actions. Shell execution, arbitrary path loads,
manual shader reload actions, and external capture hooks are not part of the schema. Artifact names are safe file-name
segments, not paths.

## Results and artifacts

A completed job reports `success`, kind, diagnostics, timings, frame IDs, artifact metadata, and an absolute
`manifest_path`. Each artifact records its absolute path, byte count, media type, format, kind, and resource metadata
when applicable. Binary data stays on disk.

Artifacts are written under `<gameDir>/vibris/artifacts/<workspace UUID>/<request UUID>`. A job writes to a sibling
`.tmp` directory and becomes visible only after its manifest and files are atomically committed. Startup recovers valid
committed jobs and removes abandoned temporary directories.

The default artifact quota is 3 GiB. Before reserving a new job, the manager evicts the oldest reported jobs until the
reservation fits. A single job larger than the quota fails; completed results remain protected until reported to the MCP
or until the unreported-result timeout expires. `vibris_get_status` exposes both used and cap bytes.

## Troubleshooting

| Code or symptom | Meaning and action |
|-----------------|--------------------|
| `INVALID_WORKTREE` | Pass an existing Git worktree, not a repository subdirectory or ordinary folder. |
| `WORKTREE_ALREADY_OWNED` | Close the other MCP for that worktree or wait for its stdin-driven shutdown. |
| `NOT_CONFIGURED` | Call `vibris_configure` successfully before running a job. |
| `INVALID_PRESET` | Refresh `vibris_list_presets`; one configured scene identifier is not accepted by Iris. |
| `SERVER_OFFLINE` / `SERVER_NOT_READY` | Start packaged Iris, load the test world, and verify the loopback address. |
| `SHADERS_DIRECTORY_MISSING` | Add a top-level `shaders` directory to the worktree or selected commit. |
| `SOURCE_CHANGED_DURING_SNAPSHOT` | Pause source edits and retry; both bounded snapshot attempts changed. |
| `SOURCE_TOO_LARGE` / `SOURCE_TOO_MANY_FILES` | Reduce the shader tree below the limits advertised by Iris. |
| `SOURCE_CONTAINS_REPARSE_POINT` | Replace links/junctions with ordinary files and directories. |
| `QUEUE_FULL` | Wait for current work; pending calls, source registry, and job queue are bounded. |
| `CAPTURE_RESOURCE_NOT_FOUND` | Choose a logical name from `vibris_get_status.resource_catalog`. |
| `ARTIFACT_JOB_TOO_LARGE` / `ARTIFACT_QUOTA_EXCEEDED` | Reduce captures or allow reported jobs to become evictable. |

If the MCP hangs during shutdown, close stdin once and inspect stderr. A clean used-client exit reports one completion
queue and at least one joined worker. Do not terminate unrelated Java or MultiMC processes; the integration harness
tracks process identity and only cleans up its owned run.

## Audit and soak

From `I:\code\vibris`:

```powershell
# Fast source/package boundary check
.\integration-tests\scripts\source-package-audit.ps1

# Short process/status baseline
.\integration-tests\scripts\soak.ps1 -Iterations 10 -SourceBytes 0 `
  -MetricsOut .omo\tmp\ulw-v1-g008-c003\metrics.json

# G008-C003 real workload: 1,000 immutable 50 MiB source reload/capture jobs
.\integration-tests\scripts\soak.ps1 -Iterations 1000 -SourceBytes 52428800 -ReloadCapture `
  -MetricsOut .omo\tmp\ulw-v1-g008-c003\metrics.json
```

`soak.ps1` prefers `mcp/out/asan/vibris-mcp.exe` when it exists and otherwise uses the release executable. It records
native handles/private bytes, sanitizer allocator metrics when available, available Java process metrics, pending-source
and artifact series, quota bounds, and shutdown evidence. The packaged Iris harness does not expose JMX, so Java live
heap and direct-buffer fields are explicitly null and the final-200 trend gate uses process private bytes as its stated
fallback. The script removes owned game/source/artifact/worktree data, then writes only the requested metrics evidence.