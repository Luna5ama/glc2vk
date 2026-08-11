# Vibris shader control

Vibris exposes one native MCP executable for shader testing. `vibris-mcp.exe` speaks newline-delimited MCP JSON-RPC
on stdin/stdout and connects to the Vibris server embedded in Iris over loopback gRPC. Iris owns Minecraft, shader
reloads, render-thread work, and capture resources; the MCP owns explicit request routing, immutable source preparation,
recipe expansion, and synchronous tool results.

```text
Codex or another MCP client
        | stdio MCP; each call carries worktree_root and, for jobs, preset_id
        v
vibris-mcp.exe --server-address 127.0.0.1:50051
        | protobuf over loopback gRPC
        v
patched Iris JAR -> Vibris core -> render-thread host adapter
        |                         |
        v                         v
vibris/pending/<source UUID>      vibris/artifacts/<workspace UUID>/<request UUID>
```

One MCP process may route requests for several ordinary or linked worktrees, and several MCP processes may also target
the same worktree. All submit to one shared Iris process. Iris schedules work round-robin by durable workspace ID, but
each job is non-interruptible and only one job touches Minecraft at a time.

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
workspace selection is request-scoped, not process-scoped. The same MCP registration can serve ordinary and linked
worktrees concurrently because every tool call supplies its exact absolute `worktree_root`.

Putting the same table in global `~/.codex/config.toml` is supported, but it makes Vibris appear in Codex tasks for
unrelated Git repositories too. Prefer the tracked project file when only shader repositories should expose the server.

For CI or a manual shell launch, start the MCP normally:

```powershell
I:\code\vibris\build\delivery\vibris-mcp.exe --server-address 127.0.0.1:50051
```

The MCP process working directory does not select a workspace. Every tool call carries an absolute `worktree_root`, and
the MCP verifies that it is exactly a Git worktree root. The server address must be the loopback endpoint published by
the running Iris instance; `127.0.0.1:50051` is the default.

On Windows, packaged-client integration probes attach a window guard to the owned Iris runtime. It minimizes that
runtime's visible windows without activating them and fails the probe if Iris reaches the foreground, so automated
packaged-client runs do not steal focus from the desktop.

Closing MCP stdin performs an orderly shutdown: pending calls are resolved, the gRPC completion queue closes, its worker
thread joins, and prepared sources still owned by the MCP are removed.

## Request-scoped worktrees and scenes

On first use, the MCP atomically creates `.vibris/workspace.json` with the durable identity shared by concurrent
MCP processes for that one worktree:

```json
{
  "schema_version": 1,
  "workspace_id": "c58a84bf-f6ee-4d53-9b31-7af03dfaf500"
}
```

Vibris does not read or migrate the former `.codex/vibris-session.json` state. The worktree-local Vibris state directory
belongs in the shader repository's `.gitignore`:

```gitignore
/.vibris/
```

Do not ignore `.codex/config.toml`; it is the tracked project configuration that linked worktrees inherit.

There is no ambient MCP scene configuration. `vibris_configure` and `vibris_get_config` do not exist. Every request
specifies its worktree, and each job-start request also specifies `preset_id`. The MCP resolves and validates that preset
against live Iris immediately before submitting the job, so alternating calls for different worktrees or scenes cannot
inherit one another's context. A single MCP process keeps independent runtime clients per durable workspace ID.

The only worktree-local durable state is identity plus explicit workflow data such as profile-matrix checkpoints and
source snapshots. These records make accepted jobs resumable; they are not defaults for later requests. Closing one MCP
process does not invalidate another process's queued or active work.

Iris reads its preset catalog from `<gameDir>/config/vibris/presets.json`. Call `vibris_list_presets` with the target
`worktree_root` when a preset identifier is not known. Each schema-v2 entry is a complete scene instead of a
cross-product of separate world, time, and camera catalogs:

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
    "settings_preset_id": "default",
    "tags": ["sky", "regression"]
  }]
}
```

`tags` is optional. Catalog entries without it retain compatibility; the standard `sky-*`,
`aerial-perspective-*`, `raster-*`, and `shadow-*` names receive their corresponding tag automatically. Returned
presets contain the complete resolved scene, sorted tags, catalog version, and a stable `preset_sha256` over the
preset identity and effective context.

The common request scope is:

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1"
}
```

`worktree_root` is required by all five tools. `preset_id` is additionally required when starting a recipe, action
sequence, or matrix. Profile-matrix status, resume, and cancel operations use their checkpointed scene and therefore
require `worktree_root` and `job_id`, but no new preset. Job receipts retain the complete resolved scene and preset
identity, including catalog version, tags, and `preset_sha256`.

Scene presets are not shader quality profiles. Shader settings remain in the independent `config`/`configs` fields of
recipes and matrices.

## MCP tools

The server negotiates MCP protocol `2024-11-05` and advertises tools only. Every tool result contains one JSON text
content item and matching structured content.

| Tool | Arguments | Result |
|------|-----------|--------|
| `vibris_list_presets` | `worktree_root`, optional text `filter` and `filter_tags` array | matching live scene presets with context, tags, version, and hash |
| `vibris_get_status` | `worktree_root` | server/runtime state, queue, resources, pending/artifact roots and quota |
| `vibris_run_recipe` | `worktree_root`, `preset_id` for starts, and one recipe form below | synchronous terminal job result or checkpointed workflow state |
| `vibris_run_actions` | `worktree_root`, `preset_id`, named sources/configs plus up to 64 actions | synchronous terminal job result and artifact metadata |
| `vibris_run_matrix` | `worktree_root`, `preset_id`, named sources/configs, selected axes, and an action template | per-case results with recorded failures |

All low-level capture and shader-debug operations are variants in the `actions` array of `vibris_run_actions`; none is
advertised as a separate MCP tool. One invocation becomes one `SubmitJob`, and result-bearing actions are returned in
execution order through `action_results` with their action index, kind, and JSON result.

Preset filters are case-insensitive. Text filtering retains substring behavior, `filter_tags` uses AND semantics, and
both filters are combined when supplied together. An omitted or empty filter lists the full catalog:

```json
{"worktree_root":"I:\\code\\shaderpack-worktree","filter":"sky-","filter_tags":["sky"]}
```

| Action types | Purpose |
|--------------|---------|
| `wait_frames` | wait for rendered frames |
| `take_screenshot`, `dump_texture`, `dump_buffer` | optionally wait for rendered frames, then write managed artifact groups |
| `get_capture_status`, `capture_pass`, `capture_multi` | inspect or queue compute and OpenGL raster draw captures; raster replay uses `vibris-replay-gl` |
| `load_shader`, `inspect_shader` | load a named source/config pair with diagnostics, or inspect current shader state |
| `get_gpu_metrics` | measure GPU pass timings over its next required `frames` |
| `list_buffers`, `dump_buffer` | inspect or dump Iris SSBOs by stable logical name |
| `list_textures`, `dump_texture` | inspect or dump textures by stable logical name |
| `get_patched_shaders` | wait for Iris patched-shader writes and capture the files as one artifact group |

Server discovery reports the complete runtime surface only as `supported_job_actions`. Recipes and matrices are
MCP overlays that expand to action sequences before gRPC submission.

The `get_gpu_metrics` action requires `{"type":"get_gpu_metrics","frames":N}` with `1 <= N <= 10000`.
Measurement starts when that action executes, timestamps exactly the next `N` rendered frames, and completes when the
final requested frame has been collected. Timing statistics use `avg`, `p5`, `p50`, and `p95`; metadata-aware capture
hosts also return exact program records described below. Callers do not need a separate wait or a prior snapshot.

`get_patched_shaders` requires a safe artifact namespace, for example
`{"type":"get_patched_shaders","artifact_name":"patched"}`. It waits for every Iris `ShaderPrinter` write submitted
before the action, then snapshots the ordinary files directly under `patched_shaders`. A source file such as
`001_begin.vsh` is published as `patched.001_begin.vsh`; JSON files retain JSON metadata and other shader/property files
are text artifacts. All files form one `patched_shaders` artifact group and share the job transaction, quota, manifest,
cancellation, and rollback boundary. A write failure or a source file changing during the copy fails the entire job.

The `profile` recipe is the high-level performance workflow. It snapshots the selected workspace or commit, activates
and reloads that source with the optional shader config, resets temporal state, waits `warmup_frames` (default 32), and
then measures exactly the next required `frames`. It returns the same aggregate and exact-program views as
the `get_gpu_metrics` action. The `profile_matrix` recipe profiles a selected source/config Cartesian product under the
same scene preset. Both recipes return the same top-level contract and a `cases` array; a single `profile` is
represented by one `source--config` case.

Every case always contains `case_id`, `source_id`, `config_id`, `status`, `error`, `frames`,
`warmup_frames`, `metrics`, `provenance`, and `barriers`. The top level declares `gpu_timing_unit: "ns"` and reports
requested, completed, with-metrics, missing, failed, retried, passed, and incomplete counts. Empty GPU samples
produce a retryable `NO_GPU_SAMPLES` case error instead of a passed result.

Successful measurements require `provenance.complete: true`. The provenance receipt includes:

- `source`: workspace/commit kind, requested revision, resolved 40-character commit, immutable source-tree SHA-256,
  active source UUID, file/byte counts, and a stable source identity hash.
- `shader`: explicit effective settings, config SHA-256, and the actual Iris patched-shader output SHA-256,
  generation, file count, and byte count.
- `scene`: the full effective save, dimension, time, weather, camera, FOV, resolution, and settings preset, plus
  context SHA-256, preset ID/version/display name, and preset SHA-256.
- `case_hash`: a stable SHA-256 over the source, config, scene, preset, and patched-shader identities.

The active source UUID and patched-shader generation remain diagnostic activation receipts and are deliberately
excluded from `case_hash`, so an identical retry/resume keeps the same case identity. A different source tree,
effective config, scene, preset, or patched shader changes the case hash. Missing runtime proof returns
`INCOMPLETE_PROVENANCE`; the case is `incomplete`, never passed.

Each checkpointed `profile_matrix` case is an isolated Core transaction. Before the first case, Core must already own
an active source and have observed its explicit shader settings and applied scene through a successful Vibris load.
Otherwise the matrix fails closed with `BENCHMARK_STATE_UNAVAILABLE`, because it cannot prove how to restore the
pre-matrix runtime state. A config declared with `mode: "preserve"` is resolved to that frozen explicit settings map;
it does not inherit unreported values from the preceding case.

An isolated case emits this ordered barrier chain: `source_published`, `config_applied`, `shader_reloaded`,
`shader_generation_confirmed`, `warmup_started`, `warmup_completed`, `sample_started`, `sample_completed`, and
`state_restored`. The shader-generation receipt binds the sampled case to the inspected patched-shader generation.
Core restores the retained source, explicit shader settings, full scene context, and reset temporal state before
publishing the case result, including after cancellation or a case failure. Missing or out-of-order receipts return
`BENCHMARK_BARRIER_FAILED`; restoration failure returns `BENCHMARK_RESTORE_FAILED` and makes Core not ready.

Every action result also carries its explicit `case_id`. Normalization and durable artifact recovery group results by
that identity, not by action-array ranges; a receipt for another case pauses the workflow without advancing its
checkpoint. `profile-result.json` stores the complete barrier receipts with the raw attributed action results.

GPU metrics expose two timing views under the same `gpu_timing_unit: "ns"` contract:

- `gpuTimings` is the flat compatibility view. A `pass_total` entry is a `framework_total`: elapsed GPU time between
  the framework debug-group push and pop, which can include several programs and non-program work. A historical
  `pass_compute` or `pass_draw` entry is a `compatibility_aggregate`: per-invocation samples collapsed across every
  program observed under that wrapper. It is not a single shader-program identity and is not the sum of the wrapper.
- `gpuTimingScopes` classifies each flat key as `framework_total` or `compatibility_aggregate` and records its
  framework pass and stage.
- `gpuProgramTimings` is the exact program view. Each record has `metric`, `kind: "program"`, `program`, `stage`,
  `source`, sorted `defines`, optional `dispatch`, `framework_pass`, `compatibility_metric`, and `statistics`.
  Program histories are keyed by the complete metadata, so `begin3` and `begin3_a` remain separate even when both
  execute inside the `begin3` wrapper.

Capture hosts publish exact identity through the metadata-aware `beginCompute(GpuTimingProgram)` or
`beginDraw(GpuTimingProgram)` overload. Calls using the legacy no-argument methods continue producing only the flat
compatibility view. This keeps existing integrations working while T09 adds program/source emission in Iris.

Profile requests accept an optional `result_detail`:

| Value | Returned detail |
|-------|-----------------|
| `summary` | stable case fields and counters; `metrics` is `null` |
| `metrics` | default; stable case fields plus one per-case GPU metrics payload |
| `full` | metrics plus non-metric per-case action results, artifacts, and terminal job metadata |

The raw `get_gpu_metrics` action result is never repeated in `action_results`; GPU timings live only under the case
`metrics` field. Every profile job also publishes an unfiltered `profile-result.json` through the normal artifact
transaction. The JSON contains the complete normalized cases and raw action results, so a compact or truncated MCP
response can be recovered without rerunning Minecraft. Set `result_csv: true` to publish a flattened
`profile-result.csv` beside it. Compact responses keep these result artifacts in their top-level `artifacts` array;
their paths are rewritten through the workspace `.vibris/artifact` link like other artifacts. Both files share the
job manifest, quota, rollback, ownership, and unreported-result protection boundary.

Use `metric_filter` to select timings in the MCP response. Each entry is an exact name or a `*` wildcard pattern.
Flat timings match their metric name. Program timings match `metric`, `program`, `stage`, `source`, `dispatch`,
`framework_pass`, `compatibility_metric`, and define names, values, or `NAME=VALUE`; for example,
`GenerateSkyViewLUT.comp.glsl` or `begin3_a` selects the exact SkyView program record instead of the `begin3` wrapper.
Use `statistics` to select any of `avg`, `p5`, `p50`, and `p95`. These filters do not remove data from
`profile-result.json`. Raw values always remain nanoseconds; `converted_units` may contain `us`, `ms`, or both to add
suffixed derived values such as `avg_us` and `avg_ms` without replacing `avg`. CSV always has `value_ns`, optionally
adds `value_us`/`value_ms`, and includes timing kind plus program, stage, source, defines, dispatch, framework pass,
and compatibility-metric columns.

Both profile recipes retry each retryable case independently. `max_retries` is bounded from 0 through 5 and defaults
to 2, so a case has at most three automatic attempts. `profile_matrix` executes its selected product as ordered
single-case profile jobs, atomically checkpointing each terminal case before submitting the next one. Only cases ending
in `NO_GPU_SAMPLES`, an explicit `retryable: true` error, or a retryable transport/runtime code are retried. Passed and
non-retryable cases are not submitted again, and exhausting one case does not stop later independent cases. Retryable
codes currently include
`SERVER_OFFLINE`, `SERVER_RESTARTED`, `SERVER_NOT_READY`, `QUEUE_FULL`, `QUEUE_TIMEOUT`, `EXECUTION_TIMEOUT`,
`WORLD_LOAD_FAILED`, `SOURCE_ACTIVATION_FAILED`, `INTERNAL_ERROR`, and `CAPTURE_FAILED`.

Each final case contains `attempt_count`, `retry_exhausted`, and a compact ordered `attempts` array. The top level adds
`total_attempts`, the configured `max_retries`, and `job_attempts`; `retried_cases` counts cases with more than one
attempt. Result artifacts from every terminal attempt are retained in the top-level `artifacts` array and annotated
with their attempt number and case IDs. Retry artifacts also embed prior attempt diagnostics in `profile-result.json`.

`benchmark_ab` builds on isolated single-case profiles for performance comparisons. Each round contains two baseline
and two candidate samples arranged as `abba`, `abab`, or a seeded balanced `randomized` order. It interleaves a second
same-commit block whose logical A and B sides both load the baseline source; the 95th percentile of those absolute
paired deltas is the measured noise floor. `rounds` and `control_rounds` default to 3 and are bounded from 2 through
20. Every nested profile uses the normal bounded `max_retries` policy and an isolation workflow identity, so Core must
publish `state_restored` before that measurement can pass.
If a nested case does not publish that receipt, the paired workflow stops before submitting another measurement.

The comparison is fail-closed. Every sample must report the requested frame count, the same effective config and scene
hashes, a stable source identity within each physical source, and the same complete aggregate/program identity sets.
A different frame count, config hash, scene hash, or exact program metadata produces `invalid_comparison`, structured
guard mismatches, and no numeric comparison table. This also catches a workspace changing between repeated samples.

Successful output includes raw per-round pair samples and a compact `comparison_table`. Each row reports baseline and
candidate median-of-round medians, absolute and percentage deltas, paired-delta mean/median/sample variance, a 95%
paired Student-t confidence interval, Tukey outlier rounds, the measured control noise floor, direction, and a
`stable`, `unstable`, or `inconclusive` verdict. An effect that does not exceed the measured noise floor or whose
confidence interval includes zero is inconclusive. `result_detail: "full"` additionally returns every normalized
nested profile receipt; compact executions, round samples, comparison rows, and result artifacts are always retained.

Add `visual` to the request to run one deterministic screenshot comparison after the performance rounds. Both sides
are loaded through the same request-scoped scene transaction: Iris disables day/time and weather advancement, restores
the exact save, dimension, time, weather, camera, FOV, and resolution, hides the HUD, resets shader temporal counters,
and renders the same warmup-frame count before each capture. The response returns `performance_verdict`,
`visual_verdict`, the combined `verdict`, and a `visual` receipt together. A visual threshold violation changes the
combined status to `completed_with_failures` and `success: false` without discarding the performance comparison or
difference artifacts.

Visual verdicts are accepted only with a successful runtime receipt, two distinct capture frame IDs, `diff.json`, a
PNG heatmap for screenshot/PNG comparisons, and exactly two successful `load_shader` receipts. Those load receipts
must identify both prepared sources and must report matching effective config and scene-context hashes. Missing or
mismatched evidence returns `INVALID_VISUAL_RECEIPT`, `status: "invalid_comparison"`, and an `inconclusive` verdict;
the `visual.guards` (or standalone `visual_guards`) object names every failed condition.

PNG visual statistics are normalized to `[0, 1]`. MAE, RMSE, p95, and maximum use channel samples;
`threshold_pixel_ratio` counts a pixel when any channel exceeds `pixel_error_threshold`. PNG comparisons also report
a global luminance SSIM. `diff.json` and one or more red difference heatmaps are committed through the normal artifact
transaction. Binary comparisons report the error statistics but have `ssim: null`; configuring `min_ssim` for a
comparison without PNG samples fails closed with `SSIM_UNAVAILABLE`. Configurable limits are
`max_mean_absolute_error`, `max_root_mean_square_error`, `max_p95_absolute_error`, `max_absolute_error`,
`max_threshold_pixel_ratio`, and `min_ssim`.

The deterministic contract controls the scene and shader clocks, but it cannot make arbitrary world content static.
Use quiet, fully loaded presets without moving entities, particles, resource streaming, or unseeded shader randomness
for visual gates. Warm the world before starting the benchmark. A noisy preset should be treated as unsuitable for a
strict pixel gate, not hidden by widening thresholds until every result passes.

Every matrix has a durable `job_id`, a workspace-local checkpoint at `.vibris/profile-matrix/<job_id>.json`, and
queue-time source snapshots below `.vibris/profile-matrix/<job_id>/sources/`. Workspace and commit sources are frozen
once before the first case; later cases, retries, and MCP restart recovery materialize fresh server-owned UUIDs from
those snapshots instead of rereading the mutable workspace. The full resolved scene and preset identity are stored in
the same checkpoint. The default `execution: "sync"` preserves the blocking call behavior. Set
`execution: "async"` to return immediately, then use the same recipe with one of these control forms:

```json
{"worktree_root":"I:\\code\\shaderpack-worktree","recipe":"profile_matrix","operation":"status","job_id":"<job-id>"}
{"worktree_root":"I:\\code\\shaderpack-worktree","recipe":"profile_matrix","operation":"resume","job_id":"<job-id>","execution":"async"}
{"worktree_root":"I:\\code\\shaderpack-worktree","recipe":"profile_matrix","operation":"cancel","job_id":"<job-id>"}
```

Status is a partial-result read: completed cases retain their final receipts, while remaining entries have
`status: "pending"`. `progress` contains `requested_cases`, receipt-oriented `completed_cases`, the one-based
`current_case_number`, `current_case_id`, and `stage`. Stages include `queued`, `loading`, `warming`, `sampling`,
`retrying`, `checkpointing`, `paused`, `cancelled`, and `completed`; `progress_stages` lists the distinct observed
stages, while `result_detail: "full"` also returns the bounded per-transition `progress_events` diagnostics.
`vibris_get_status` includes a compact view of the currently active
`profile_matrix_job` snapshot.

Cancellation stops the in-flight Core request at its cancellation boundary and keeps every checkpointed receipt.
`resume` starts at the first case without a final receipt. On MCP restart, the persisted Core request ID is resumed
first; if the Core terminal cache was lost but its committed `profile-result.json` exists, that artifact is recovered
as the case receipt instead of measuring the case again. If neither receipt exists, only the unfinished case is
submitted again with the full stored scene configuration and explicit source/config load. Transport/queue exhaustion
pauses the workflow with a structured `last_error`. If Core already accepted the request and its terminal state is
uncertain, `last_error.details.resume_required` is true and the request is not submitted again until `resume` proves
that replay is safe. `SERVER_OFFLINE`, `SERVER_RESTARTED`, RST_STREAM transport loss, deadline, and queue failures can
then be resumed without discarding earlier cases. One MCP process runs at most one matrix workflow at a time, and its
checkpoint is capped at 64 MiB.

For the 19-preset release acceptance, keep scene presets separate from shader configs. Enumerate the typed preset
catalog, pass one `preset_id` directly to each two-source matrix request containing
the baseline commit and candidate workspace with one preserved shader config. Repeat for all 19 presets and aggregate
the 19 two-case results into exactly 38 receipts. Reject the acceptance if any result reports fewer than two requested
cases, a passed case has empty metrics, a timing omits its real `program` or `source`, `gpu_timing_unit` is not `ns`,
or the combined unique `(preset_id, source_id)` count is not 38. Interrupted jobs must be resumed by `job_id`; do not
resubmit already checkpointed cases.

`integration-tests/scripts/live-benchmark-acceptance.ps1` automates that release gate against an already running
Minecraft instance. It first performs one explicit workspace/config load so Core owns a restorable source, shader
settings map, and scene; preserve-mode matrices otherwise fail closed with `BENCHMARK_STATE_UNAVAILABLE`. The script
then puts `spawn` first, interrupts only the MCP process it launched after receipt 1/2, resumes the same `job_id`, runs
the remaining typed presets, and finishes with a paired performance plus deterministic PNG visual gate. The release
gate defaults to `spawn`, four visual warmup frames, `pixel_error_threshold: 0.015`, and a strict
`max_threshold_pixel_ratio: 0.001`. The pixel threshold was calibrated from two clean HEAD-versus-identical-workspace
captures: both had fewer than 0.095% of pixels above 3/255 channel error, while the remaining MAE, RMSE, p95, maximum,
and SSIM limits stayed independently enforced. `-VisualPresetId` and `-VisualWarmupFrames` support a different
prevalidated scene without making these release thresholds a universal preset. It requires
the exact `begin3_a`, `composite13_a`, and `composite34` program records and their real source files, then writes the
compact 38-receipt evidence below the shader worktree's `.vibris/artifact` directory. It never launches, stops, or
restarts Minecraft:

```powershell
pwsh -NoProfile -File integration-tests/scripts/live-benchmark-acceptance.ps1 `
  -McpExe $deliveryRoot/vibris-mcp.exe `
  -WorkspaceRoot $shaderWorktree `
  -BaselineRevision HEAD `
  -VisualPresetId 'spawn' `
  -VisualWarmupFrames 4
```

This direct in-game path replaces project-local wrappers for routine profiling. Compute capture and external
replay/Nsight analysis remain separate diagnostic workflows.

Example request:

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "profile",
  "source": {"kind": "workspace"},
  "config": {"SETTING_GI_SPATIAL_REUSE_COUNT": 14},
  "warmup_frames": 32,
  "frames": 120,
  "result_detail": "metrics",
  "metric_filter": ["composite18_total", "shadowcomp*"],
  "statistics": ["avg", "p50"],
  "converted_units": ["us", "ms"],
  "max_retries": 2,
  "result_csv": true
}
```

Debug dumps use the running Minecraft instance and execute on its client thread. Optional compute-capture paths are
resolved inside the game directory; paths escaping it are rejected.

### Shader config

Single-source recipes accept an optional top-level `config`. Action and matrix requests declare reusable named configs;
`load_shader` references one config by ID. It closes any open screen, hides the HUD, loads the selected source and
config, reloads the shader pipeline, applies the request-scoped scene, and resets temporal counters. Boolean, number, and
printable ASCII string values are converted to Iris `KEY=VALUE` properties before the shader load:

```json
{
  "configs": [{
    "id": "quality",
    "values": {
      "SETTING_CLOUDS_CU_WIND": false,
      "SETTING_GI_SPATIAL_REUSE_COUNT": 14,
      "TITLE_VERSION": 5
    }
  }]
}
```

Single-source recipes preserve the current Iris options file when `config` is omitted. A named
`{"id":"current","mode":"preserve"}` config has the same effect. Passing an empty `values` object writes an empty
options file, so Iris uses shader-pack defaults. Option names and values are validated, and each encoded config is
limited to 64 KiB.

The runtime writes the properties file beside `pending_shaders_root`, at `../config/vibris.txt`, then links
`.minecraft/shaderpacks/vibris.txt` to it. This keeps the frequently rewritten file on the same RAM disk as pending
sources when `pending_shaders_root` is configured there. If the platform cannot create the link, the runtime writes
`.minecraft/shaderpacks/vibris.txt` directly. Iris loads that exact file for the fixed `vibris` shader pack.

There are no MCP resources and no aliases for these tool names. Unknown methods use JSON-RPC `-32601`; invalid tool
arguments use a structured tool error rather than expanding the tool surface.

## Sources

Single-source recipes accept either source form:

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

Action and matrix requests use named declarations, so one immutable snapshot can be loaded with several configs:

```json
{
  "sources": [
    {"id":"base","kind":"commit","revision":"HEAD~1"},
    {"id":"candidate","kind":"workspace"}
  ],
  "configs": [
    {"id":"steep","values":{"SETTING_PARALLAX_MODE":1}},
    {"id":"spline","values":{"SETTING_PARALLAX_MODE":4}}
  ]
}
```

## Recipes

Recipes exist only in the native MCP: it expands each single recipe or checkpointed matrix case into a bounded action
sequence before submitting the job. The protobuf boundary and Minecraft runtime carry and execute action sequences
only; no recipe enum or recipe decoder exists in the mod.

### Profile

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "profile",
  "source": {"kind": "workspace"},
  "config": {"SETTING_PARALLAX_MODE": 4},
  "warmup_frames": 32,
  "frames": 120,
  "result_detail": "metrics",
  "metric_filter": ["composite18_total", "shadowcomp*"],
  "statistics": ["avg", "p50"],
  "converted_units": ["us"],
  "max_retries": 2,
  "result_csv": true
}
```

`profile_matrix` accepts `sources`, `configs`, and `matrix` axes in the same form as `vibris_run_matrix`, then profiles
every selected source/config combination. A failed or incomplete combination is recorded with explicit source/config
identity and later combinations continue.

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "profile_matrix",
  "sources": [{"id":"base","kind":"commit","revision":"HEAD~1"},
              {"id":"candidate","kind":"workspace"}],
  "configs": [{"id":"spawn","mode":"preserve"}],
  "matrix": {"sources":["base","candidate"],"configs":["spawn"]},
  "frames": 120,
  "max_retries": 2,
  "execution": "async"
}
```

### Paired performance comparison

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "benchmark_ab",
  "baseline": {"kind": "commit", "revision": "HEAD~1"},
  "candidate": {"kind": "workspace"},
  "config": {"SETTING_PARALLAX_MODE": 4},
  "warmup_frames": 32,
  "frames": 120,
  "rounds": 5,
  "control_rounds": 3,
  "order": "abba",
  "statistic": "avg",
  "metric_filter": ["begin3_a", "composite_total"],
  "max_retries": 2,
  "result_detail": "metrics",
  "visual": {
    "warmup_frames": 32,
    "pixel_error_threshold": 0.01,
    "max_mean_absolute_error": 0.002,
    "max_root_mean_square_error": 0.004,
    "max_p95_absolute_error": 0.01,
    "max_absolute_error": 0.10,
    "max_threshold_pixel_ratio": 0.001,
    "min_ssim": 0.995
  }
}
```

The baseline source is also used for both sides of every control round. Use `random_seed` with `order: "randomized"`
to reproduce a balanced randomized schedule. The response exposes the actual execution order, all per-round samples,
guard receipts, measured noise, and the final compact comparison table in nanoseconds.

### Load and screenshot

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "load_and_screenshot",
  "source": {"kind": "workspace"},
  "warmup_frames": 32,
  "screenshot_format": "png"
}
```

Loads the source and config, waits the requested frames, and saves one screenshot.

### Capture a debug bundle

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
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
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "recipe": "ab_compare",
  "a": {"label": "baseline", "source": {"kind": "commit", "revision": "HEAD"}},
  "b": {"label": "candidate", "source": {"kind": "workspace"}},
  "warmup_frames": 32,
  "visual_thresholds": {
    "pixel_error_threshold": 0.01,
    "max_threshold_pixel_ratio": 0.001,
    "min_ssim": 0.995
  },
  "captures": [
    {"type": "screenshot", "format": "png"},
    {"type": "texture", "name": "colortex0.main", "format": "bin"}
  ]
}
```

Runs both variants under the same request-scoped scene and capture specification. Capture targets are screenshot PNG,
texture BIN/PNG, and buffer BIN. The result includes comparison metrics, threshold verdict and violations, a JSON
metrics artifact, difference heatmap artifacts, and the two sets of artifact groups. Without `visual_thresholds` it
reports metrics with `verdict: "not_evaluated"`; with thresholds, any violation makes the recipe unsuccessful.

## Custom actions

The full action set is listed in the MCP table above. `take_screenshot`, `dump_texture`, and `dump_buffer`
all write through the same artifact transaction, quota, commit, and rollback path. Texture and buffer dumps include
a JSON sidecar; 3D PNG dumps produce one Z-ordered layer file per slice. No dump writes into the game directory.

```json
{
  "worktree_root": "I:\\code\\shaderpack-worktree",
  "preset_id": "sky-noon-1",
  "sources": [{"id":"candidate","kind":"workspace"}],
  "configs": [{"id":"parallax","values":{"SETTING_PARALLAX_MODE":0}}],
  "actions": [
    {"type": "load_shader", "source": "candidate", "config": "parallax"},
    {"type": "take_screenshot", "after_frames": 32, "format": "png", "artifact_name": "beauty"},
    {"type": "get_gpu_metrics", "frames": 120},
    {"type": "list_textures"},
    {"type": "list_buffers"},
    {"type": "dump_texture", "name": "colortex0.main", "format": "bin", "artifact_name": "colortex0"},
    {"type": "dump_buffer", "name": "iris_ssbo_6", "artifact_name": "ssbo-6"}
  ]
}
```

`take_screenshot` waits `after_frames` rendered frames, captures the final framebuffer, and does not complete until the
PNG is committed as a managed artifact. The delay defaults to zero, so no scheduling or result-poll action is needed.

`load_shader` requires explicit source and config IDs. Its action result contains `success`, the requested source/config
identity, the post-load `pack_loaded` and `shaderpack` state, current shader `errors`, structured reload `diagnostics`,
and a compile-log path on failure. A failed explicit load is returned as a failed action result and skips later actions
in that source/config case. Use `inspect_shader` only when inspecting the current runtime without loading a source.
Sequences without `load_shader` operate on the current runtime.
A/B recipes may also add an internal capture-comparison action. The request-scoped scene is applied when a shader is
loaded. Actions never expose shell execution, arbitrary source paths, or external process
hooks. Managed artifact names are safe flat file-name segments, and optional compute-capture paths must stay within the
game directory.

## Matrix actions

`vibris_run_matrix` executes one action template for the source-major Cartesian product selected by `matrix.sources`
and `matrix.configs`. Each combination automatically begins with `load_shader`; do not include `load_shader` in the
action template. Artifact names are prefixed with the case ID.
Shader or action failures are returned on that case and execution continues with the next case. Transport, source
preparation, cancellation, and server failures remain job-global.

## Results and artifacts

A completed job reports `success`, kind, diagnostics, timings, frame IDs, artifact metadata, and an absolute
`manifest_path`. Each artifact records its absolute path, byte count, media type, format, kind, and resource metadata
when applicable. Binary data stays on disk.

Artifacts are written below configured `artifact_root` with the exact workspace ID as the first directory:
`<artifact_root>/<workspace_id>/<request directory>`. A job
writes to a sibling `.tmp` directory and becomes visible only after its manifest and files are atomically committed.
Startup recovers valid committed jobs and removes abandoned temporary directories.

When an MCP receives a terminal response containing artifact, manifest, or diagnostic-log paths, it ensures that
`.vibris/artifact` is a directory link to `<artifact_root>/<workspace_id>` and rewrites every returned path through that
link. This keeps artifact paths inside the worktree permission boundary. A missing or stale link is created or replaced;
if `.vibris/artifact` is occupied by a normal file or directory, the tool fails with `ARTIFACT_LINK_ERROR`.

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
| `NOT_CONFIGURED` | A `load_shader` action referenced a named shader config absent from the same request. |
| `INVALID_PRESET` | Refresh `vibris_list_presets` for the same worktree; the request's `preset_id` is not accepted by Iris. |
| `SERVER_OFFLINE` / `SERVER_NOT_READY` | Start packaged Iris, load the test world, and verify the loopback address. |
| `SHADERS_DIRECTORY_MISSING` | Add a top-level `shaders` directory to the worktree or selected commit. |
| `SOURCE_CHANGED_DURING_SNAPSHOT` | Pause source edits and retry; both bounded snapshot attempts changed. |
| `SOURCE_TOO_LARGE` / `SOURCE_TOO_MANY_FILES` | Reduce the shader tree below the limits advertised by Iris. |
| `SOURCE_CONTAINS_REPARSE_POINT` | Replace links/junctions with ordinary files and directories. |
| `QUEUE_FULL` | Wait for current work; pending calls, source registry, and job queue are bounded. |
| `SERVER_STATE_BUSY` / `RUNTIME_STATE_RELOADING_SHADERS` | A shader compile or safe-point rollback is still running. Keep the accepted request ID and resume or queue work; do not reconfigure or restart solely because compilation exceeds a client deadline. |
| `NO_GPU_SAMPLES` | The profile case returned no non-empty GPU timing set; retry it or inspect runtime readiness. |
| `INCOMPLETE_PROVENANCE` | Source, config, scene, or patched-shader identity is incomplete; do not compare. |
| `BENCHMARK_STATE_UNAVAILABLE` | Load a known source/config/scene through Vibris before starting an isolated matrix. |
| `BENCHMARK_BARRIER_FAILED` | A case identity or isolation receipt is missing or inconsistent; do not compare. |
| `BENCHMARK_RESTORE_FAILED` | The retained pre-matrix source/config/scene could not be restored; Core is not ready. |
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

# One native MCP routing explicit requests across two worktrees
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
