# Vibris Benchmark Reliability Roadmap

## Goal

Turn the agent feedback into a reliable, resumable benchmark workflow whose results prove:

1. every requested case actually produced GPU samples;
2. every result belongs to the requested source, config, scene, and shader generation;
3. batch failures can retry or resume without discarding completed cases;
4. timings identify the real shader program being measured;
5. A/B conclusions are based on paired, repeated measurements rather than one sequential matrix run.

The active implementation branch is codex/vibris-benchmark-reliability, based on
50b0d227d9ff55c6bc2cf74b8a0dc61d02d478c7. Work happens directly in I:\code\vibris.
The pre-existing untracked capture/a.spv is user data and remains outside every task and commit.

## Goal execution protocol

Every continuation of this goal must follow this protocol:

1. Read this entire document before making changes.
2. Run git status --short --branch and verify the expected branch/worktree.
3. Select only the first Pending task whose dependencies are Done.
4. Work on that task only. Do not opportunistically implement later tasks.
5. Add or update focused automated tests and run the task's verification commands.
6. Update this document with the task status and durable verification evidence.
7. Stage only the task's files plus this document.
8. Run git diff --cached --check, inspect the staged diff, and create one atomic commit.
9. End the goal round after the commit. The next goal round rereads this document and starts the next task.
10. Mark the goal complete only when every required task is Done and the final acceptance task passes.

If a task is blocked, leave it Pending, record the blocker under its task section, do not mix partial product
changes into another task, and end the round with the goal still active.

Do not restart Minecraft or its launcher unless the user explicitly asks.

## Status board

| ID | Priority | Repository | Task | Status | Commit title |
|---|---|---|---|---|---|
| T00 | P0 | vibris | Persist roadmap and isolated worktree protocol | Done | docs: add benchmark reliability roadmap |
| T01 | P0 | vibris | Fail closed on missing GPU samples and report completeness counters | Done | fix(mcp): fail incomplete gpu profile results |
| T02 | P0 | vibris | Normalize compact profile result schema and explicit timing units | Done | feat(mcp): normalize profile result contract |
| T03 | P1 | vibris | Add pass/statistic filters and publish full results as an artifact | Done | feat(mcp): add filtered profile output |
| T04 | P0 | vibris | Retry incomplete matrix cases without rerunning completed cases | Done | feat(mcp): retry incomplete profile cases |
| T05 | P0 | vibris | Checkpoint matrix cases and expose resumable progress | Done | feat(mcp): checkpoint profile matrix progress |
| T06 | P0 | vibris | Return exact scene/config/source provenance | Done | feat(mcp): add benchmark case provenance |
| T07 | P0 | vibris | Isolate cases and restore state | Done | fix(core): isolate benchmark matrix cases |
| T08 | P1 | vibris | Preserve real shader-program timing identity and source metadata | Done | feat(capture): expose program gpu timings |
| T09 | P1 | Iris | Emit distinct program timing labels for grouped wrapper programs | Done | feat(shaderdev): expose per-program gpu timings |
| T10 | P1 | vibris | Add interleaved repeated A/B benchmarking and noise evaluation | Done | feat(mcp): add paired benchmark recipe |
| T11 | P2 | vibris | Add typed preset selection, tags, and tag filtering | Done | feat(mcp): add typed preset filters |
| T12 | P2 | vibris/Iris | Add deterministic visual A/B gate and final live acceptance | Done | feat(core): add benchmark visual gate |

## Task details

### T00 - Persist roadmap and isolation protocol

Scope:

- Create this roadmap.
- Record the isolated worktree and branch.
- Define the one-task-per-goal-round and one-commit-per-task protocol.

Acceptance:

- I:\code\vibris owns codex/vibris-benchmark-reliability directly.
- The temporary setup worktree has been removed.
- The pre-existing untracked capture/a.spv remains untouched.
- This document is committed alone on top of the selected main baseline.

Verification:

- git status --short --branch
- git diff --cached --check
- git diff --cached --stat

### T01 - Fail closed on incomplete GPU metrics

Dependencies: T00.

Primary files:

- mcp/src/main/cpp/synchronous_job_runner.cpp
- mcp/src/test/cpp/job_protocol_tests.cpp
- relevant synchronous test fixtures

Scope:

- A profile result is complete only when get_gpu_metrics exists, succeeded, and contains a non-empty gpuTimings object.
- Empty or missing samples produce a structured NO_GPU_SAMPLES error.
- A profile_matrix case with empty samples is incomplete or failed, never passed.
- Add requested_cases, completed_cases, cases_with_metrics, missing_cases, failed_cases, and retried_cases counters.
- Top-level success/status must not claim complete success while any requested case lacks metrics.

Acceptance:

- Tests cover missing action results, null metrics, empty gpuTimings, successful metrics, and mixed matrix cases.
- A 38-case request can never report complete success unless all 38 cases have metrics.

Verification:

- Build and run the MCP C++ test target containing job_protocol_tests.
- Run the existing MCP schema and protocol tests affected by the result mapper.

### T02 - Normalize the public profile result contract

Dependencies: T01.

Primary files:

- mcp/src/main/cpp/synchronous_job_runner.cpp
- mcp/src/main/cpp/tool_registry.cpp
- mcp/src/test/cpp/action_schema_tests.cpp
- mcp/src/test/cpp/job_protocol_tests.cpp
- docs/capture-control.md

Scope:

- profile and profile_matrix both return a stable cases array.
- Every case includes case_id, source_id, config_id, status, error, frames, warmup_frames, and metrics.
- Add gpu_timing_unit with the explicit value ns.
- Add result_detail with summary, metrics, and full values.
- Eliminate duplicated top-level/action/case timing payloads according to result_detail.
- Do not rely on array order to map results back to a case.

Acceptance:

- Schema tests reject unsupported detail levels.
- Snapshot-style tests prove each detail level has the documented fields and no duplicated metrics.

### T03 - Filter metrics and persist the complete result artifact

Dependencies: T02.

Scope:

- Add pass/program filters and statistic filters to profile and profile_matrix.
- Support compact/summary responses without discarding the full data.
- Persist the full JSON result and optional CSV under the job artifact directory.
- Return artifact paths in the compact response.
- Preserve raw nanosecond values and optionally include converted microsecond/millisecond values.

Acceptance:

- Filtering returns only requested metrics/statistics.
- A truncated MCP response is recoverable from the artifact without rerunning Minecraft.
- Artifact ownership and quota rules remain unchanged.

### T04 - Retry incomplete cases

Dependencies: T01, T02.

Scope:

- Add bounded per-case retry configuration, defaulting to two retries for NO_GPU_SAMPLES and retryable transport/runtime errors.
- Keep completed case results and submit only missing/retryable cases.
- Keep every attempt in artifact diagnostics while returning one final case result.
- One failed case must not invalidate later independent cases.

Acceptance:

- Tests simulate an empty first attempt followed by a successful retry.
- Tests prove completed cases are not submitted twice.
- Exhausted retries remain explicit failures with attempt counts.

### T05 - Checkpoint and resume matrix execution

Dependencies: T04.

Scope:

- Persist case completion after every case.
- Expose job_id plus requested/completed/current case progress.
- Resume after SERVER_OFFLINE, SERVER_RESTARTED, RST_STREAM, deadline, or queue failure when safe.
- Restore/reconfigure runtime state automatically when existing protocol evidence makes recovery safe.
- Support partial-result reads and cancellation without losing completed receipts.

Acceptance:

- A simulated interruption after case 17 of 38 resumes at case 18.
- Restart recovery does not duplicate completed case measurements.
- Progress identifies loading, warming, sampling, retrying, and completed stages.

### T06 - Case provenance and effective context

Dependencies: T02.

Scope:

- Return requested revision and resolved full commit SHA.
- Return immutable source snapshot hash, patched shader hash/generation, active source UUID, and config hash.
- Return the full effective scene context and effective shader settings.
- Include save, dimension, camera, time, weather, FOV, resolution, and preset version/hash.
- Workspace sources are snapshotted when queued, not reread later.

Acceptance:

- A case result proves exactly which source/config/scene was measured.
- Provenance remains stable across retry and resume.
- Different effective state cannot share the same reported case hash.

### T07 - Case isolation and state restoration

Dependencies: T05, T06.

Scope:

- Add explicit barriers for source publication, shader reload, config application, shader generation, warmup start, and sample start.
- Start every case from an explicit config snapshot; preserve mode cannot leak undeclared values.
- Restore the pre-matrix source/config/scene after completion or cancellation.
- Attribute action results by explicit case identity rather than sequence ranges alone.

Acceptance:

- Regression tests cover config leakage, delayed shader reload, result misattribution, and final restoration.
- A 128/512/1024-style matrix cannot shift one case's metrics into the next case.

### T08 - Program-level GPU timing model in Vibris

Dependencies: T02.

Primary files:

- capture/src/main/kotlin/dev/luna5ama/vibris/capture/GpuTimingMetrics.kt
- capture/src/main/kotlin/dev/luna5ama/vibris/capture/ShaderDebugControl.kt
- capture/src/main/kotlin/dev/luna5ama/vibris/capture/ShaderDebugModels.kt
- API/protocol files required to carry timing identity

Before editing capture, read capture/AGENTS.md in full.

Scope:

- Preserve distinct runtime program identifiers instead of collapsing wrapper groups.
- Return program name, stage, source file, optional defines/dispatch identity, and timing statistics.
- Keep compatibility aliases for existing aggregate keys where required.
- Define how framework/aggregate total differs from a real program measurement.

Acceptance:

- begin3 and begin3_a can be reported separately.
- GenerateSkyViewLUT.comp.glsl can be selected and timed by source/program identity.
- Tests cover multiple programs under one wrapper group.

### T09 - Iris timing emission

Dependencies: T08.

Repository protocol:

- Inspect I:\code\Iris status and AGENTS.md before editing.
- If its active worktree is dirty, create a separate codex/ worktree from the intended base.
- Commit only Iris timing-emission changes in the Iris repository.
- Record the resulting commit evidence in this roadmap during the following Vibris round.

Scope:

- Emit unique labels for begin1_a/b, begin2_a/b, begin3_a, composite13_a, composite34, and other grouped programs.
- Attach the resolved shader source and relevant program metadata.
- Preserve existing aggregate timing labels for compatibility.

Acceptance:

- Live or fixture evidence distinguishes GenerateSkyViewLUT from other begin3 work.
- DirectLighting/Sky is distinguishable from the rest of composite13.

### T10 - Paired benchmark recipe

Dependencies: T04, T06, T07.

Scope:

- Add a benchmark_ab/profile_compare recipe using ABBA, ABAB, or randomized order.
- Support repeated rounds and median-of-runs aggregation.
- Report absolute delta, percentage, variance/confidence interval, outliers, and a stable/unstable/inconclusive verdict.
- Add same-commit control cases and a measured noise floor.
- Refuse or flag comparisons with different frames, effective config hashes, scene hashes, or program identity.
- Restore the original runtime state when finished.

Acceptance:

- Tests cover order, paired aggregation, noise-floor rejection, and mismatched sample guards.
- The response includes both per-round samples and a compact comparison table.

### T11 - Typed preset discovery and filtering

Dependencies: T02.

Scope:

- Accept a typed preset config form with preset_id.
- Return the resolved complete scene context and preset version/hash.
- Add preset tags and filter_tags while retaining text filtering.
- Keep scene presets distinct from shader quality profiles.

Acceptance:

- The known 19-preset catalog can be filtered by sky, aerial-perspective, raster, and shadow tags.
- Typed preset input is validated by schema instead of trial and error.

### T12 - Deterministic visual gate and final acceptance

Dependencies: T03 through T11.

Scope:

- Capture baseline/candidate images at the same deterministic simulation state.
- Report MAE, RMSE, p95/max error, threshold pixel ratio, SSIM when available, and a difference artifact.
- Allow a benchmark case to fail when visual error exceeds configured thresholds.
- Run a final 19 presets x 2 sources acceptance matrix with failure/retry injection.
- Keep long, synchronous shader compilation at a runtime safe point after client timeout and report the active request as
  `BUSY` / `RELOADING_SHADERS` instead of latching Core into `FAILED`.
- Update public documentation with the final supported workflow and limitations.

Acceptance:

- Exactly 38 requested case receipts are present.
- Empty metrics cannot pass.
- Interrupted runs resume without duplicating completed cases.
- Every timing names its real program/source and unit.
- Invalid A/B comparisons are rejected.
- Performance and visual verdicts are returned together.

Completion evidence (2026-08-09):

- The visual-gate implementation, public documentation, focused tests, full Gradle build, and Release CTest suite are
  complete offline. The automated acceptance fixture sends 19 distinct request-scoped scene presets and runs one
  two-source matrix with one preserved shader config per preset. It injects an empty-sample retry and an interruption
  after receipt 17, resumes at receipt 18, and verifies 38 unique metric receipts with exact program/source metadata
  and `gpu_timing_unit: "ns"`. Visual receipts additionally fail closed unless both load receipts prove matching
  scene/config hashes and include source identities, two distinct frames, `diff.json`, and the PNG heatmap.
- Long shader compilation exposed three unsafe fixed waits: five seconds after execution cancellation, five seconds for
  the rollback reload, and ten seconds for isolated benchmark restoration. Those waits could mutate the active source
  while Iris was still compiling and then permanently mark the activator not ready. Core now joins each non-cancellable
  runtime operation to its actual safe point. Status uses the last healthy runtime snapshot while a request is active,
  publishes `active_request_id`, and maps shader/world stages to `BUSY`, `RELOADING_SHADERS`, or `LOADING_WORLD`.
- Live smoke testing caught that the real `1.21.11-shaderdev` build branch initially omitted the T09 Iris commit. The
  already-tested change was cherry-picked onto the build branch as Iris commit
  `27b7c0c6bf5e18e7f3c60a2f0564a7db0b1f43db`; `:common:test :fabric:remapJar --offline` passed, and a user-controlled
  restart loaded the rebuilt JAR.
- The restarted runtime completed the live 19-preset x 2-source matrix with exactly 38 unique receipts. The `spawn`
  pair survived an MCP-only interruption after receipt 1/2, resumed the same job without duplication, and every case
  returned `begin3_a` / `GenerateSkyViewLUT.comp.glsl`, `composite13_a` / `DirectLighting.glsl`, and `composite34` /
  `EpipolarScattering.comp.glsl` timings in nanoseconds. The reusable evidence is
  `I:\code\mcshaders\Alpha-Piscium\.vibris\artifact\t12-live-acceptance-20260809-1213.json.matrix.json`.
- The first paired benchmark exposed two integration omissions: Core rejected nested result artifact kind
  `benchmark_ab`, and the paired MCP runner dropped the request-scoped scene-preset provenance from its nested profile and
  visual requests. Both are fixed with focused Core and paired-runner tests. All 16 live paired measurements now pass
  provenance, exact metric identity, equal frame/config/scene guards, and final runtime restoration.
- The updated delivery is published at `I:\code\vibris\build\delivery-benchmark-reliability` with MCP SHA-256
  `806C04F2EB1BEA10E3F25FECA61B414E025A06C67474D787A32E34237EEBFEC2` and Iris SHA-256
  `BAAB2D314AFDEEE3A267F9BFEC64901EB5077A661BC06572074DD41B8918F795`; the on-disk MultiMC mod has the same Iris hash.
- `integration-tests/scripts/live-benchmark-acceptance.ps1` drives the real release gate against an already running MC:
  it primes an explicit restorable state, runs `spawn` first, interrupts only its own MCP after receipt 1/2, resumes the
  same job without duplication, completes the other 18 typed presets, verifies exactly 38 program-level receipts and
  the three required program/source mappings, then runs the paired performance and PNG visual gate. The final evidence
  is `I:\code\mcshaders\Alpha-Piscium\.vibris\artifact\t12-live-acceptance-final-6.json`, SHA-256
  `C29CF6CF689533DE7FE2FFDF852FAE9348B909E423A4A50E92E54D7AAACE9EFA`: 38/38 unique passed receipts, 16/16 paired
  measurements, three exact program identities, `performance_verdict: "inconclusive"`, passed performance/visual
  guards, restored runtime state, and `visual_verdict: "passed"`. The calibrated same-snapshot PNG receipt reports
  MAE 0.001115421, RMSE 0.002248306, p95 0.003921569, max 0.035294118, threshold-pixel ratio 0.000943769 below the
  0.001 limit, and SSIM 0.999646876; `diff.json` and its PNG heatmap are present.
- Final offline verification passed `./gradlew.bat build --offline`, the full CMake Release build and CTest suite
  (60/60), focused long-reload status and 4096-file duplicate-validation races, invalid paired comparison/visual
  receipt guards, and the shortened 100-cycle `SourceSoak` (9.64 seconds). The adversarial test harness now allows
  slow Windows source validation without weakening its 4096-file duplicate-submission race.

## Completion log

Add one entry here in the same commit that completes each task. Record the date, task ID, verification commands,
and exact commit title. The Git history is the source of truth for the resulting SHA.

- 2026-08-09 - T00 - Roadmap created, rebased onto 50b0d22, and assigned to the original I:\code\vibris
  worktree. Verified with git status --short --branch, git diff --cached --check, and staged-diff inspection.
  Commit title: docs: add benchmark reliability roadmap.
- 2026-08-09 - T01 - Added retryable NO_GPU_SAMPLES failures for single profiles; matrix cases with empty,
  null, or missing gpuTimings are incomplete instead of passed; explicit action failures remain failed; summaries
  report requested/completed/with-metrics/missing/failed/retried counts. Fixed the profile-matrix case-size
  off-by-one needed for correct attribution. Verified by building vibris-job-protocol-tests and
  vibris-action-schema-tests, then running CTest cases SynchronousRecipeResultMapping and
  ActionSchemaRejectsForbiddenAndDuplicateTools (2/2 passed), including an exact 38-case missing-result
  regression. Commit title: fix(mcp): fail incomplete gpu profile results.
- 2026-08-09 - T02 - Normalized profile and profile_matrix into one cases-array contract with explicit case/source/config
  identity, stable status/error/frame/metrics fields, gpu_timing_unit=ns, and summary/metrics/full result detail.
  Summary omits timing payloads, metrics is the default, and full retains non-metric action/job detail without
  duplicating gpuTimings. Unsupported detail values fail schema validation. Updated capture-control documentation.
  Verified by building vibris-job-protocol-tests and vibris-action-schema-tests, then running CTest cases
  SynchronousRecipeResultMapping and ActionSchemaRejectsForbiddenAndDuplicateTools (2/2 passed).
  Commit title: feat(mcp): normalize profile result contract.
- 2026-08-09 - T03 - Added wildcard metric/pass filters, avg/p5/p50/p95 statistic filters, and optional us/ms
  derived values while retaining raw nanoseconds. Every profile now writes an unfiltered full profile-result.json,
  with optional flattened CSV, inside the existing core ArtifactManager transaction; compact responses return those
  artifact paths. Added protocol, schema, mapping, filtering, conversion, manifest, quota, and recovery-data tests.
  Verified with .\gradlew.bat :vibris-core:test, CMake release build, and the full release CTest suite (51/51 passed).
  Commit title: feat(mcp): add filtered profile output.
- 2026-08-09 - T04 - Added bounded per-case retries (default 2, maximum 5) for NO_GPU_SAMPLES and retryable
  transport/runtime failures. Completed and non-retryable cases are retained while only affected matrix cases are
  resubmitted as single profiles; each final case reports ordered attempt diagnostics, attempt_count, and explicit
  retry exhaustion, while all terminal-attempt artifacts remain attributable by attempt and case ID. Added schema,
  protocol, core artifact, empty-sample recovery, retryable job failure, non-retryable failure, continuation, and
  exhaustion coverage. Verified with .\gradlew.bat :vibris-core:test, CMake release build, focused retry/schema CTest
  cases (2/2 passed), and the full release CTest suite (51/51 passed). Commit title: feat(mcp): retry incomplete
  profile cases.
- 2026-08-09 - T05 - Changed profile matrices to ordered single-case jobs with an atomic workspace checkpoint after
  every receipt, durable job IDs, synchronous or asynchronous execution, partial status reads, stop-token
  cancellation, and explicit resume control. Progress reports requested/completed/current case plus loading, warming,
  sampling, retrying, checkpointing, paused, cancelled, and completed stages. Persisted Core request IDs resume cached
  terminals across MCP restart; a committed profile-result.json is recovered when the Core terminal cache was lost,
  while accepted requests with uncertain terminal state pause before any resubmission, preventing a completed
  measurement from being submitted again. Added a 17/38 interruption regression, restart no-duplication proof,
  cancellation/resume receipt preservation, accepted-timeout safety, 38-case schema coverage, JobProgress delivery,
  resumed attempt history, artifact recovery, and in-flight cancellation tests. Verified with
  .\gradlew.bat :vibris-core:test, CMake release build, focused workflow/schema/protocol CTest cases (3/3 passed), and
  the full release CTest suite (52/52 passed). Commit title: feat(mcp): checkpoint profile matrix progress.
- 2026-08-09 - T06 - Added fail-closed per-case provenance for the exact Core-owned source tree, requested revision,
  resolved full commit, active source UUID, explicit shader settings/config hash, full runtime-applied scene, preset
  version/hash, and actual patched-shader hash/generation. Stable case hashes exclude transient activation UUIDs and
  generations while binding all effective source/config/scene/patched content. Profile matrices now freeze each
  workspace or commit source and resolved scene once into the durable queue checkpoint, then materialize fresh
  server-owned UUIDs from that snapshot across cases, retries, and MCP restart recovery. Added same-content retry,
  changed-config, active-source mutation, patched-output generation, preset-version, queue-time workspace mutation,
  and restart-resume coverage. Verified with .\gradlew.bat build --offline, the CMake release build, focused
  provenance/workflow tests, and the full release CTest suite (53/53 passed). Commit title: feat(mcp): add benchmark
  case provenance.
- 2026-08-09 - T07 - Added isolated matrix-case transactions with durable workflow/case identity and ordered receipts
  for source publication, explicit config application, shader reload/generation confirmation, warmup, sampling, and
  final state restoration. Preserve configs now resolve from the retained pre-matrix snapshot; Core restores the
  original source/config/scene and resets temporal state before publishing a result, including cancellation paths.
  Action and artifact mapping now uses explicit case IDs, with fail-closed mismatch and 128/512/1024 metric-shift
  regressions. Verified with .\gradlew.bat build --offline, the CMake release build, focused isolation/workflow tests,
  and the full release CTest suite (53/53 passed). Commit title: fix(core): isolate benchmark matrix cases.
- 2026-08-09 - T08 - Added exact program-level GPU timing records keyed by program, stage, source, defines, and
  dispatch identity while retaining classified framework totals and compatibility aggregate aliases. MCP filters now
  select program records by metric, program, source, dispatch, and defines; Core JSON/CSV artifacts preserve metadata,
  and program-only payloads require real statistics before counting as complete. Tests cover `begin3` and `begin3_a`
  under one wrapper plus `GenerateSkyViewLUT.comp.glsl` source/program selection. Verified with
  `.\gradlew.bat build --offline`, the CMake Release build, focused Capture/Core/native tests, and the full Release
  CTest suite (53/53 passed). Commit title: feat(capture): expose program gpu timings.
- 2026-08-09 - T09 - Iris compute dispatches now emit their real program ID, the source-map file containing the
  active `main`, and cached direct-dimension or indirect-offset metadata through `GpuTimingProgram`; existing
  aggregate dispatch overloads remain available. Fixture coverage preserves `begin1_a/b`, `begin2_a/b`, `begin3_a`,
  `composite13_a`, and `composite34`, and distinguishes `GenerateSkyViewLUT.comp.glsl` and `DirectLighting.glsl` from
  their wrapper work. Verified with `ComputeProgramTimingTest` (3/3 passed),
  `.\gradlew.bat :common:test :fabric:build --offline`, and `.\gradlew.bat build --offline`. Iris implementation
  commit: bac171f611a29909ba547f5b7da21b7f2495ab9e. Commit title: feat(shaderdev): expose per-program gpu timings.
- 2026-08-09 - T10 - Added repeated paired performance profiling with ABBA, ABAB, or seeded balanced randomized
  order, per-round median aggregation, same-baseline control rounds, measured p95 noise floors, paired variance and
  Student-t confidence intervals, Tukey outlier reporting, and stable/unstable/inconclusive verdicts. Comparisons now
  fail closed before producing a numeric table when frames, effective config/scene hashes, stable physical-source
  identity, exact metric/program identity, or final state-restoration receipts differ. Every measurement reuses the
  bounded profile retry path and an isolated workflow identity; results include compact executions, raw per-round
  samples, controls, artifacts, and a comparison table. Verified with `.\gradlew.bat build --offline`, the full
  CMake Release build, focused paired/schema/runner tests (6/6 passed), and the full Release CTest suite (57/57 passed).
  Commit title: feat(mcp): add paired benchmark recipe.
- 2026-08-09 - T11 - Added schema-validated `{kind:"preset", preset_id}` scene selection while retaining the legacy
  complete-scene form. Configure and get-config receipts now include the resolved save/dimension/time/weather/camera,
  FOV, resolution, settings preset, catalog version, tags, and stable preset SHA-256. Preset JSON accepts explicit
  tags and infers sky, aerial-perspective, raster, and shadow tags for the existing 19-preset naming scheme; discovery
  supports case-insensitive text filtering plus AND-combined `filter_tags`, including empty text filters. Shader
  quality configs remain a separate recipe/matrix model. Verified with `.\gradlew.bat build --offline`, the CMake
  Release build, focused preset/schema/runner tests (4/4 passed), and the full Release CTest suite (58/58 passed).
  Commit title: feat(mcp): add typed preset filters.
- 2026-08-09 - T12 - Added deterministic PNG A/B statistics, configurable fail-closed thresholds, JSON metrics and
  PNG heatmap artifacts, combined paired performance/visual verdicts, safe-point joining and truthful busy/reload
  status for long shader compilation, nested benchmark artifact/preset provenance, and the durable live acceptance
  driver. The live gate passed 19 presets x 2 sources with 38/38 unique metric receipts, an MCP restart after 1/2
  receipts without duplication, exact `begin3_a`, `composite13_a`, and `composite34` source mappings, 16/16 guarded
  paired measurements, restored runtime state, and a passing visual receipt. Verified with `.\gradlew.bat build
  --offline`, the CMake Release build, full Release CTest (60/60, including 100-cycle SourceSoak), focused adversarial
  status/validation tests, and live evidence `t12-live-acceptance-final-6.json` with SHA-256
  `C29CF6CF689533DE7FE2FFDF852FAE9348B909E423A4A50E92E54D7AAACE9EFA`. Commit title: feat(core): add benchmark
  visual gate.
