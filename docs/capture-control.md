# Vibris shader control

Vibris exposes one native MCP executable for shader testing. `vibris-mcp.exe` speaks newline-delimited MCP JSON-RPC
on stdin/stdout and connects to the Vibris server embedded in Iris over loopback gRPC. Iris owns Minecraft, shader
reloads, render-thread work, and capture resources; the MCP owns worktree configuration, immutable source preparation,
recipe expansion, and synchronous tool results.

```text
Codex or another MCP client
        | stdio MCP; process cwd is inside the shader worktree
        v
vibris-mcp.exe --server-address 127.0.0.1:50051
        | protobuf over loopback gRPC
        v
patched Iris JAR -> Vibris core -> render-thread host adapter
        |                         |
        v                         v
vibris/pending/<source UUID>      vibris/artifacts/<workspace UUID>/<request UUID>
```

Each Codex task may run its own MCP process, including several processes discovered from the same Git worktree. All MCP
processes submit to one shared Iris process. Iris schedules work round-robin by durable workspace ID, but each job is
non-interruptible and only one job touches Minecraft at a time.

## Build

Requirements are Windows, Java 21 for the shared JVM modules, Java 24 for the replay modules, CMake 3.25+, Visual
Studio 2022, vcpkg, and the sibling `Iris` checkout. Set `VCPKG_ROOT` before configuring the native build.

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

Build a verified two-file delivery with both repository roots explicit:

```powershell
Set-Location I:\code\vibris
.\tools\build-delivery.ps1 -VibrisRoot I:\code\vibris -IrisRoot I:\code\Iris
```

The delivery build rebuilds the native MCP and patched Iris JAR before publishing them to `build\delivery`; it does not
package stale pre-existing artifacts.

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

Start the packaged Iris client first. For normal Codex use, track this file as `.codex/config.toml` in each trusted
shader repository. TOML literal strings keep Windows paths readable:

```toml
[mcp_servers.vibris]
command = 'I:\code\vibris\build\delivery\vibris-mcp.exe'
args = [
  '--server-address',
  '127.0.0.1:50051',
]
```

Codex loads a trusted project's `.codex/config.toml` as project-scoped configuration. Do not set the MCP `cwd` field:
the process inherits the Codex task cwd, and Vibris searches upward from that nested directory to the containing Git
worktree. A linked worktree checks out the same tracked project configuration and discovers its own worktree root from
that task cwd. This keeps one config portable across ordinary and linked worktrees without embedding shader-repository
paths.

Putting the same table in global `~/.codex/config.toml` is supported, but it makes Vibris appear in Codex tasks for
unrelated Git repositories too. Prefer the tracked project file when only shader repositories should expose the server.

For CI or a manual shell launch outside the worktree, retain the explicit override:

```powershell
I:\code\vibris\build\delivery\vibris-mcp.exe `
  --workspace-root I:\code\shaderpack-worktree `
  --server-address 127.0.0.1:50051
```

`--workspace-root` must be a real Git worktree containing a `shaders` directory. If it is omitted, the MCP searches
upward from its current directory. The server address must be the loopback endpoint published by the running Iris
instance; `127.0.0.1:50051` is the default.

On Windows, packaged-client integration probes attach a window guard to the owned Iris runtime. It minimizes that
runtime's visible windows without activating them and fails the probe if Iris reaches the foreground, so automated
packaged-client runs do not steal focus from the desktop.

Closing MCP stdin performs an orderly shutdown: pending calls are resolved, the gRPC completion queue closes, its worker
thread joins, and prepared sources still owned by the MCP are removed.

## Worktree configuration

On first use, the MCP atomically creates `.codex/vibris-workspace.json` with the durable identity shared by concurrent
MCP processes for that one worktree:

```json
{
  "schema_version": 1,
  "workspace_id": "c58a84bf-f6ee-4d53-9b31-7af03dfaf500"
}
```

A valid legacy `.codex/vibris-session.json` contributes only its schema-v1 UUID when this identity is first created; the
legacy file is not rewritten and its old scene fields are not loaded. Invalid legacy content is ignored for migration
and a new UUID is generated.

Both generated state files belong in the shader repository's `.gitignore`:

```gitignore
.codex/vibris-workspace.json
.codex/vibris-session.json
```

Do not ignore `.codex/config.toml`; it is the tracked project configuration that linked worktrees inherit.

Scene configuration is process-local. Every new MCP process starts with `configured=false` and `config=null`, even when
the durable workspace ID already exists, so every Codex task must call `vibris_configure` before submitting jobs.
Concurrent processes discovered from the same worktree share the durable ID but can keep different scenes and prepared
sources without cross-talk. Closing one process does not invalidate another process's queued or active work.

Each successful `vibris_configure` is validated against live Iris presets. Iris also records the last validated context
in its game directory through `ConfiguredContextStore`; that single global value is only the next Iris-startup default,
not persisted MCP task configuration. Runtime jobs still carry the calling process's scene.

Iris reads its preset catalog from `<gameDir>/config/vibris/presets.json`. Use `vibris_list_presets` before configuring
when a preset identifier is not known. Each schema-v2 entry is a complete scene instead of a cross-product of separate
world, time, and camera catalogs:

```json
{
  "schema_version": 2,
  "presets": [{
    "id": "rooftop",
    "save_id": "shader-test-world",
    "save_name": "shader-test-world",
    "dimension_id": "minecraft:overworld",
    "position": [124.5, 82.0, -31.5],
    "yaw": 137.0,
    "pitch": -8.0,
    "fov": 70.0,
    "tick": 12000,
    "weather": "clear",
    "resolution": [1920, 1080],
    "settings_preset_id": "default"
  }]
}
```

The configure request uses that same preset ID for `time_preset_id` and `camera_preset_id`.

## MCP tools

The server negotiates MCP protocol `2024-11-05` and advertises tools only. Every tool result contains one JSON text
content item and matching structured content.

| Tool | Arguments | Result |
|------|-----------|--------|
| `vibris_get_config` | empty object | configured flag, worktree root, workspace ID, process-local scene config |
| `vibris_list_presets` | optional non-empty `filter` | matching live preset catalog entries |
| `vibris_configure` | save, dimension, time, camera, FOV, default warmup frames | validated process-local scene config |
| `vibris_get_status` | empty object | server/runtime state, queue, resources, pending/artifact roots and quota |
| `vibris_profile` | source/config, optional warmup, required frame count | direct future-frame GPU timing aggregates |
| `vibris_run_recipe` | one recipe form below | synchronous terminal job result and artifact metadata |
| `vibris_run_actions` | optional source plus up to 64 actions | synchronous terminal job result and artifact metadata |

All low-level capture and shader-debug operations are variants in the `actions` array of `vibris_run_actions`; none is
advertised as a separate MCP tool. One invocation becomes one `SubmitJob`, and result-bearing actions are returned in
execution order through `action_results` with their action index, kind, and JSON result.

| Action types | Purpose |
|--------------|---------|
| `reset_temporal_state`, `wait_frames` | control temporal history and rendered-frame waits |
| `capture_screenshot`, `capture_texture`, `capture_buffer` | write same-frame managed artifacts |
| `get_capture_status`, `capture_pass`, `capture_multi` | inspect or queue compute captures |
| `reload_shader`, `get_shader_status`, `get_shader_errors` | reload and inspect the active shader pack |
| `schedule_screenshot`, `get_screenshot_result` | schedule and locate an asynchronous host screenshot |
| `get_gpu_metrics` | measure GPU pass timings over its next required `frames` |
| `list_ssbos`, `dump_ssbo` | inspect or dump SSBOs by binding index |
| `list_textures`, `dump_texture` | inspect or dump textures by logical name or OpenGL ID |
| `list_patched_shaders` | inspect patched shader debug files |

Server discovery reports the complete runtime surface only as `supported_job_actions`. Recipes and profiling are
MCP-only overlays: each expands to an action sequence before gRPC submission, while Minecraft only decodes and executes
atomic actions.

The `get_gpu_metrics` action requires `{"type":"get_gpu_metrics","frames":N}` with `1 <= N <= 10000`. Measurement
starts when that action executes, timestamps exactly the next `N` rendered frames, and completes when the final requested
frame has been collected. Its result contains only aggregate timing fields (`avg`, `p5`, `p50`, and `p95`); callers do
not need a separate wait or a prior snapshot.

`vibris_profile` is the high-level performance workflow. It snapshots the selected workspace or commit, activates and
reloads that source with the optional shader config, resets temporal state, waits `warmup_frames` (or the configured
default), and then measures exactly the next required `frames`. It returns the same `avg`, `p5`, `p50`, and `p95`
aggregates as the `get_gpu_metrics` action. Repeat the tool with different config objects for a controlled settings
matrix; the scene preset remains fixed by `vibris_configure`, which must succeed before profiling. The result also
includes the requested `frames` and effective `warmup_frames`. This direct in-game path replaces project-local wrappers
for routine profiling. Compute capture and external replay/Nsight analysis remain separate diagnostic workflows and are not used by
`vibris_profile`.

Example request:

```json
{
  "source": {"kind": "workspace"},
  "config": {"SETTING_GI_SPATIAL_REUSE_COUNT": 14},
  "warmup_frames": 32,
  "frames": 120
}
```

Debug dumps use the running Minecraft instance and execute on its client thread. Optional compute-capture paths are
resolved inside the game directory; paths escaping it are rejected.

### Shader config

`vibris_profile`, `vibris_run_recipe`, and source-bearing `vibris_run_actions` calls accept an optional top-level
`config` JSON object for source activation. A `reload_shader` action can instead carry its own `config`. Boolean, number,
and printable ASCII string values are converted to Iris `KEY=VALUE` properties before the shader reload:

```json
{
  "config": {
    "SETTING_CLOUDS_CU_WIND": false,
    "SETTING_GI_SPATIAL_REUSE_COUNT": 14,
    "TITLE_VERSION": 5
  }
}
```

Omitting `config` preserves the current Iris options file. Passing an empty object writes an empty options file, so Iris
uses the shader pack defaults. Option names and values are validated, and the encoded config is limited to 64 KiB.

The runtime writes the properties file beside `pending_shaders_root`, at `../config/vibris.txt`, then links
`.minecraft/shaderpacks/vibris.txt` to it. This keeps the frequently rewritten file on the same RAM disk as pending
sources when `pending_shaders_root` is configured there. If the platform cannot create the link, the runtime writes
`.minecraft/shaderpacks/vibris.txt` directly. Iris loads that exact file for the fixed `vibris` shader pack.

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

The MCP writes each immutable snapshot below configured `pending_shaders_root` as `<pending_shaders_root>/<UUID>`.
gRPC carries only the prepared source UUID, origin, file count, and total byte count. Iris re-inspects and owns that
directory before accepting the job; source file contents are never embedded in protobuf messages. The active source is
retained until the next activation, while stale or rejected sources are removed.

## Recipes

Prefer `vibris_run_recipe` when one of the three standard jobs fits. Recipes exist only in the native MCP: it expands
each recipe into a bounded action sequence before submitting the job. The protobuf boundary and Minecraft runtime carry
and execute action sequences only; no recipe enum or recipe decoder exists in the mod.

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

Use `vibris_run_actions` when a recipe cannot express the sequence. The full action set is listed in the MCP table
above. Same-frame artifacts use `capture_screenshot`, `capture_texture`, and `capture_buffer`; live debug resource dumps
use `dump_texture` and `dump_ssbo`, so their ownership and result semantics remain unambiguous.

```json
{
  "actions": [
    {"type": "reload_shader", "config": {"SETTING_PARALLAX_MODE": 0}},
    {"type": "get_shader_status"},
    {"type": "get_shader_errors"},
    {"type": "get_gpu_metrics", "frames": 120},
    {"type": "list_textures"},
    {"type": "list_ssbos"}
  ]
}
```

When `source` is present the MCP prepends its source activation; without it, the sequence operates on the current
runtime. A/B recipes may also add internal source activations and a capture-comparison action. The configured scene is
applied when a source is activated. Actions never expose shell execution, arbitrary source paths, or external process
hooks. Managed artifact names are safe flat file-name segments, and optional compute-capture paths must stay within the
game directory.

## Results and artifacts

A completed job reports `success`, kind, diagnostics, timings, frame IDs, artifact metadata, and an absolute
`manifest_path`. Each artifact records its absolute path, byte count, media type, format, kind, and resource metadata
when applicable. Binary data stays on disk.

Artifacts are written below configured `artifact_root` as `<artifact_root>/<workspace UUID>/<request UUID>`. A job
writes to a sibling `.tmp` directory and becomes visible only after its manifest and files are atomically committed.
Startup recovers valid committed jobs and removes abandoned temporary directories.

The default artifact quota is 3 GiB. Before reserving a new job, the manager evicts the oldest reported jobs until the
reservation fits. A single job larger than the quota fails; completed results remain protected until reported to the MCP
or until the unreported-result timeout expires. `vibris_get_status` exposes both used and cap bytes.

Queue capacity and artifact quota are global to the one Iris runtime. Round-robin scheduling prevents one workspace ID
from taking consecutive turns while other workspace IDs are ready, but all workspaces still share `max_global_queue`
and the artifact quota. A noisy repository can therefore consume shared admission or storage capacity; use
`vibris_get_status`, bounded captures, and retryable `QUEUE_FULL` handling rather than assuming per-repository limits.

## Troubleshooting

| Code or symptom | Meaning and action |
|-----------------|--------------------|
| `INVALID_WORKTREE` | Pass an existing Git worktree, not a repository subdirectory or ordinary folder. |
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
# Rebuild and publish the explicit delivery used by Codex
.\tools\build-delivery.ps1 -VibrisRoot I:\code\vibris -IrisRoot I:\code\Iris

# Fast source/package boundary check
.\integration-tests\scripts\source-package-audit.ps1

# Native cwd discovery: concurrent same-root processes plus an independent repository
.\integration-tests\scripts\worktree-concurrency-probe.ps1 `
  -Exe .\mcp\out\build\Release\vibris-mcp.exe `
  -WorkspaceRoot .\.omo\tmp\ulw-v1-g002-c002\worktree `
  -MalformedConfig .\integration-tests\fixtures\mcp\config-oversize.json

# Packaged same-worktree isolation: two MCP tasks and one Iris runtime
.\integration-tests\scripts\same-worktree-acceptance.ps1 `
  -DeliveryRoot .\build\delivery -TimeoutSeconds 600

# Packaged linked-worktree plus independent-repository acceptance
.\integration-tests\scripts\final-acceptance.ps1 -Mode Green `
  -WorktreeCount 3 -JobsPerWorktree 2 -MinecraftCount 1 `
  -DeliveryRoot .\build\delivery -TimeoutSeconds 600

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
