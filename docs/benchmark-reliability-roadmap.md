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
| T02 | P0 | vibris | Normalize compact profile result schema and explicit timing units | Pending | feat(mcp): normalize profile result contract |
| T03 | P1 | vibris | Add pass/statistic filters and publish full results as an artifact | Pending | feat(mcp): add filtered profile output |
| T04 | P0 | vibris | Retry incomplete matrix cases without rerunning completed cases | Pending | feat(mcp): retry incomplete profile cases |
| T05 | P0 | vibris | Checkpoint matrix cases and expose resumable progress | Pending | feat(mcp): checkpoint profile matrix progress |
| T06 | P0 | vibris | Return effective scene/config/source provenance for every case | Pending | feat(mcp): add benchmark case provenance |
| T07 | P0 | vibris | Enforce case reload barriers, isolation, and final state restoration | Pending | fix(core): isolate benchmark matrix cases |
| T08 | P1 | vibris | Preserve real shader-program timing identity and source metadata | Pending | feat(capture): expose program gpu timings |
| T09 | P1 | Iris | Emit distinct program timing labels for grouped wrapper programs | Pending | feat(shaderdev): expose per-program gpu timings |
| T10 | P1 | vibris | Add interleaved repeated A/B benchmarking and noise evaluation | Pending | feat(mcp): add paired benchmark recipe |
| T11 | P2 | vibris | Add typed preset selection, tags, and tag filtering | Pending | feat(mcp): add typed preset filters |
| T12 | P2 | vibris/Iris | Add deterministic visual A/B gate and final live acceptance | Pending | feat(core): add benchmark visual gate |

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
- Update public documentation with the final supported workflow and limitations.

Acceptance:

- Exactly 38 requested case receipts are present.
- Empty metrics cannot pass.
- Interrupted runs resume without duplicating completed cases.
- Every timing names its real program/source and unit.
- Invalid A/B comparisons are rejected.
- Performance and visual verdicts are returned together.

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
