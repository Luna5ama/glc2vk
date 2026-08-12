# Vibris engineering validation v2 serial execution ledger

This Git-tracked file is the durable source of truth for the breaking Vibris engineering-validation v2 implementation.

## Ledger identity

- Owner repository: `I:\code\vibris`
- Ledger path: `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`
- Worktree: `I:\code\vibris`
- Branch: `main`
- Baseline HEAD: `9784197c0361290b39c3c892d7edcda7c82e04cb`
- Iris repository: `I:\code\Iris`
- Iris branch: `1.21.11-shaderdev`
- Iris baseline HEAD: `298099f4f12a48fc5968a8846790ae0d78639105`
- Created: `2026-08-11`
- Source handoff: `C:\Users\Luna5ama\.codex\attachments\45a3afb1-e7e8-4092-8fcb-e16af79e9805\pasted-text.txt`, the approved v2 implementation plan in the originating Codex task, and the completed `docs\benchmark-reliability-roadmap.md` T00-T12 baseline.
- Protected pre-existing changes: Vibris untracked `capture\a.spv`; Iris untracked `.codex\`, `.vibris\`, and
  `common\logs\`. The previously protected tracked Vibris adapter/test and Iris lifecycle changes were committed by
  the user as `b7a0931d85042442c0360a38c50e30d811be9486` and
  `6322cb2833edfddbfa64d0ac6001988c4d49efd1` respectively and are no longer dirty.

Every Goal continuation must read this file completely before inspecting code, editing, validating, staging, or committing. Source handoffs are background evidence; this ledger owns task order, state, constraints, and completion criteria.

## Goal

Replace the current Vibris control surface with a breaking v2 engineering-validation service that has compact typed MCP contracts, truthful shared-runtime status, durable resumable jobs, transactional restoration and recovery, complete effective settings and provenance, compile validation, statistically guarded A/B decisions, managed artifacts, and exact texture and buffer dumps after named Iris passes, with no v1 compatibility code or data migration paths.

Execution rule:

> One Goal continuation completes at most one ordered task. A task is `DONE` only after its scope, acceptance, verification, durable evidence, and atomic commit are complete.

## Goal execution protocol

At the start of every continuation:

1. Read this file completely.
2. Verify both declared repositories, worktrees, branches, HEADs, worktree lists, and protected dirty state.
3. Reconcile completed task claims with Git history and select only the first task marked `READY`.
4. Read all repository-local instructions that govern that task's files.
5. Plan and perform only that task.
6. Run the declared focused verification and broader checks proportional to risk.
7. Record concise durable evidence in this ledger.
8. Stage only task-owned files plus this ledger when the task is owned by Vibris.
9. Inspect `git diff --cached --check`, names, stat, and the complete staged diff before committing.
10. Verify the post-commit HEAD, subject, branch, and remaining dirty state.
11. End the continuation. The same Goal starts the next round.

For an Iris-owned task, commit only Iris files first and end the continuation. On the next continuation, verify the external full SHA, then make a Vibris ledger-only receipt commit that marks the same task `DONE` and promotes the next task. Never pretend the repositories share one atomic commit.

## Global constraints

- Work directly on Vibris `main` and Iris `1.21.11-shaderdev`; do not create feature branches or alternate implementation worktrees.
- The baseline includes request-scoped routing commit `b918ee709a7fbb2f9afdc682396e313615e17513` as the direct parent of the Vibris baseline.
- This is a clean breaking v2 cutover: remove v1 schemas, aliases, parsers, adapters, fallback branches, dual-read, dual-write, deprecated fields, and migration code in the affected Vibris surfaces.
- Existing useful operations may remain only when expressed as first-class v2 contracts; they must not be accepted through old wire shapes.
- Protocol/config/checkpoint/manifest version mismatches fail with an explicit `UNSUPPORTED_VERSION`; old data remains untouched on disk but is never read, indexed, migrated, or deleted automatically.
- All user-visible outputs continue through the request worktree's `.vibris\artifact` path.
- Do not restart Minecraft or its launcher. Do not deploy the MCP server or mod unless the user explicitly authorizes that separate action.
- Pass-boundary capture v2 covers only named begin, prepare, deferred, composite, final, and shadow-composite stages; high-frequency gbuffer, terrain, and ordinary shadow draws are out of scope.
- Artifact TTL defaults to 168 hours.
- `cmake` and `ctest` are not on `PATH`; ledger commands use the Visual Studio copies under `C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin`.
- Preserve all unrelated and pre-existing user changes; never stage a whole repository.
- Keep Vibris and Iris commits atomic and separate.

## Protected and user-owned state

| Repository | Path or state | Owner | Required handling |
|---|---|---|---|
| `I:\code\vibris` | `capture\a.spv` untracked | User | Never read as a fixture, modify, delete, stage, or commit. |
| `I:\code\vibris` | `core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt` and its test | User change committed as `b7a0931d85042442c0360a38c50e30d811be9486` | Resolved; the files are clean and may be modified by later task-owned work. |
| `I:\code\vibris` | Detached review worktrees under `I:\code\vibris-review-*` | User/review tooling | Do not modify, remove, or use as implementation targets. |
| `I:\code\Iris` | `.codex\` untracked | User/Codex runtime | Preserve and never stage. |
| `I:\code\Iris` | `.vibris\` untracked | Runtime artifacts | Preserve and never stage. |
| `I:\code\Iris` | `common\logs\` untracked | Runtime logs | Preserve and never stage. |
| `I:\code\Iris` | `common\src\main\java\net\irisshaders\iris\vibris\IrisVibrisLifecycle.java` | User change committed as `6322cb2833edfddbfa64d0ac6001988c4d49efd1` | Resolved; the file is clean and may be modified by later task-owned work. |
| Both | Any later unrelated dirty file | User until proven otherwise | Stop, classify ownership, and keep it outside task staging. |

## Status board

| ID | Priority | Repository | Task | Status | Expected commit title |
|---|---|---|---|---|---|
| T00 | P0 | Vibris | Persist v2 execution ledger | DONE | `T00 persist engineering validation v2 ledger` |
| T01 | P0 | Vibris | Replace protocol with strict v2 wire contract | DONE | `T01 replace control protocol with strict v2` |
| T02 | P0 | Vibris | Publish compact typed MCP v2 tools | DONE | `T02 publish compact typed MCP v2 tools` |
| T02A | P0 | Vibris | Restore a strict v2 Core compilation baseline | DONE | `T02A migrate Core directly to strict v2` |
| T03 | P0 | Vibris | Add truthful runtime lease and status waiting | DONE | `T03 expose runtime lease and status transitions` |
| T04 | P0 | Vibris | Generalize durable resumable jobs | DONE | `T04 add durable resumable workflow jobs` |
| T05 | P0 | Vibris | Add transactional restoration and recovery | DONE | `T05 make runtime mutations transactional` |
| T06 | P0 | Vibris | Define effective shader settings contract | DONE | `T06 expose resolved shader settings contract` |
| T07 | P0 | Iris | Implement effective settings in Iris host | DONE | `T07 report effective shader settings from Iris` |
| T08 | P1 | Vibris | Return one ordered receipt per action | DONE | `T08 return complete ordered action receipts` |
| T09 | P0 | Vibris | Define compile catalog runtime contract | DONE | `T09 define compile validation catalog contract` |
| T10 | P0 | Iris | Emit complete Iris compile catalog | DONE | `T10 emit Iris program compile catalog` |
| T11 | P0 | Vibris | Add compile_validate recipe | DONE | `T11 add compile validation recipe` |
| T12 | P0 | Vibris | Expand immutable benchmark provenance | DONE | `T12 expand benchmark provenance and stale checks` |
| T12A | P0 | Vibris | Normalize strict-v2 execution receipts | DONE | `T12A normalize strict v2 execution receipts` |
| T13 | P0 | Vibris | Enforce statistical benchmark guardrails | DONE | `T13 enforce benchmark semantic guardrails` |
| T14 | P0 | Vibris | Replace artifacts with managed v2 manifests | DONE | `T14 add managed artifact v2 lifecycle` |
| T15 | P0 | Vibris | Define named pass resource dump contract | DONE | `T15 define named pass resource dump contract` |
| T16 | P0 | Iris | Implement named Iris pass boundary hooks | DONE | `T16 capture resources after named Iris passes` |
| T17 | P0 | Vibris | Integrate after-pass texture and buffer jobs | DONE | `T17 integrate after-pass resource dump jobs` |
| T18 | P0 | Vibris | Complete strict v2 cutover and documentation | DONE | `T18 complete strict v2 cutover` |
| T19 | P0 | Vibris | Run offline integrated acceptance | DONE | `T19 verify offline v2 integration` |
| T20 | P0 | Vibris/Iris | Run live two-worktree 720p acceptance | BLOCKED | `T20 record live v2 acceptance` |
| T99 | P0 | Vibris | Final integrated audit | PENDING | `T99 finalize engineering validation v2` |

## Task details

### T00 — Persist v2 execution ledger

Status: `DONE`

Dependencies: none

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`

Scope:

- Persist this ledger, the breaking-v2 decisions, serial execution protocol, exact task queue, cross-repository rules, live baselines, and protected state.

Non-scope:

- Do not change product behavior or any other tracked file.

Acceptance:

- All live repository identities and protected paths are recorded accurately.
- Every planned task has complete dependencies, scope, non-scope, acceptance, verification, expected commit title, blockers, and evidence.
- Exactly T00 is `READY` before completion, and T01 is promoted when T00 is marked `DONE`.
- The ledger checker passes before and after the T00 status transition.

Verification:

- `python C:\Users\Luna5ama\.codex\skills\run-persistent-roadmap\scripts\check_ledger.py I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`
- `git diff --cached --check`
- `git diff --cached --name-only`
- `git diff --cached --stat`
- Inspect the complete staged diff.

Expected commit title: `T00 persist engineering validation v2 ledger`

Blockers:

- None.

Evidence:

- Live identity verified: Vibris `main` at `9784197c0361290b39c3c892d7edcda7c82e04cb`; Iris `1.21.11-shaderdev` at `298099f4f12a48fc5968a8846790ae0d78639105`; `b918ee709a7fbb2f9afdc682396e313615e17513` is a Vibris baseline ancestor.
- Initial checker receipt: `Ledger valid: 22 task(s); next=T00; READY=1, PENDING=21, BLOCKED=0, DONE=0, SUPERSEDED=0.`
- Protected `capture\a.spv`, Iris runtime directories, detached review worktrees, and the two concurrent ThreadBound adapter modifications are recorded and excluded from staging.
- Final ledger transition promoted only T01; checker and staged-diff receipts are recorded in this task's commit workflow.
- Commit: this task's commit with subject `T00 persist engineering validation v2 ledger`.

### T01 — Replace protocol with strict v2 wire contract

Status: `DONE`

Dependencies: T00

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\proto\vibris_control.proto`
- `I:\code\vibris\protocol-java\`
- `I:\code\vibris\mcp\src\test\cpp\`

Scope:

- Replace the protocol with major version 2 messages for status, jobs, action receipts, resources, artifacts, compile catalogs, provenance, and after-pass dumps.
- Add a mandatory v2 handshake/version assertion and regenerate Java/C++ bindings through the normal builds.
- Remove v1 fields, enums, parsers, and protocol fixtures rather than reserving compatibility behavior.

Non-scope:

- Do not implement MCP presentation, runtime behavior, Iris hooks, or old-version translation.

Acceptance:

- Descriptor output contains only v2 contracts required by this ledger.
- A v1 or missing version handshake fails with `UNSUPPORTED_VERSION` before job submission.
- No generated bindings are checked in unless the existing build already treats them as maintained sources.

Verification:

- `cmake --preset windows-vs2022`
- `cmake --build --preset release --target vibris-protocol-smoke vibris-descriptor-dump`
- `ctest --preset release -R "descriptor-dump|protocol-smoke"`
- `.\gradlew.bat :vibris-protocol-java:test :vibris-api:test --offline`

Expected commit title: `T01 replace control protocol with strict v2`

Blockers:

- None known.

Evidence:

- Replaced the descriptor package and Java package with `vibris.control.v2` / `dev.vibris.protocol.v2`; the generated contract contains status/lease/jobs, typed receipts, effective settings, compile catalog, provenance, managed artifacts, and both named-pass dump messages without v1 reserved aliases.
- Added Java and C++ version gates with protocol major 2 and exact rejection code `UNSUPPORTED_VERSION`; tests prove v1 and missing-version submit envelopes are rejected before payload handling.
- `.\gradlew.bat :vibris-protocol-java:test :vibris-api:test --offline` passed: 27 actionable Gradle tasks; `ProtocolEnvelopeTest` 5 tests, 0 failures/errors/skips.
- Visual Studio CMake configure completed, and native targets `vibris-protocol-smoke`, `vibris-descriptor-dump`, and `vibris-protocol-version-tests` built successfully.
- `ctest --preset release -R 'descriptor-dump|protocol-smoke|protocol-version'` passed 5/5 tests, including explicit v1 and missing-version rejection.
- Generated descriptor: `I:\code\vibris\mcp\out\build\Release\vibris_control_descriptor.bin`, 28,486 bytes, SHA-256 `133C6799D61CD28F8236D013A0D36A6CBA3E3B49EDD40D7EA8CB8B31B87C37AE`.
- Generated Java/C++ bindings remain build outputs and no generated binding is staged.
- Commit: this task's commit with subject `T01 replace control protocol with strict v2`.

### T02 — Publish compact typed MCP v2 tools

Status: `DONE`

Dependencies: T01

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\tool_registry.cpp`
- `I:\code\vibris\mcp\src\main\cpp\mcp_backend.cpp`
- `I:\code\vibris\mcp\src\main\cpp\result_mapper.cpp`

Scope:

- Publish exactly the v2 tools `vibris_get_status`, `vibris_list_presets`, `vibris_list_resources`, `vibris_run_actions`, `vibris_run_matrix`, `vibris_run_recipe`, `vibris_job`, and `vibris_artifacts`.
- Make every input typed, require `recipe` as the recipe discriminator, remove top-level `oneOf`, and move all job control to `vibris_job`.
- Add `schema_version: 2` and `outputSchema`; return a bounded text summary and exactly one full structured payload.
- Make status summary omit resources and make exact preset/resource filters first-class.

Non-scope:

- Do not retain hidden v1 tools, aliases, old recipe shapes, or duplicate JSON output.

Acceptance:

- `tools/list` reports exactly eight tools with concrete argument schemas and no `args: unknown`.
- Summary text is at most 2 KiB, and complete output exists only once in `structuredContent`.
- Old recipe control operations and physical texture-name suffix aliases are rejected.

Verification:

- `cmake --build --preset release --target vibris-action-schema-tests vibris-state-tests vibris-stdio-fixture-test`
- `ctest --preset release -R "ActionSchema|ToolMetadata|ResourceLists|Stdio"`
- Run the stdio fixture and inspect `tools/list` plus one representative result.

Expected commit title: `T02 publish compact typed MCP v2 tools`

Blockers:

- None known.

Evidence:

- Published exactly eight tools: `vibris_get_status`, `vibris_list_presets`, `vibris_list_resources`, `vibris_run_actions`, `vibris_run_matrix`, `vibris_run_recipe`, `vibris_job`, and `vibris_artifacts`; every definition carries `schema_version: 2`, a concrete object `inputSchema` without a top-level `oneOf`, and an `outputSchema`.
- Recipe inputs require the `recipe` discriminator, job query/result/cancel/resume operations exist only on `vibris_job`, exact preset/resource filters are first-class, and compact status results omit the resource catalog.
- Result envelopes contain one text summary capped at 2,048 bytes and one full payload only under `structuredContent`; schema tests use a 16 KiB marker payload to prove it is not duplicated.
- Schema validation rejects old recipe control fields, `list_textures`, `list_buffers`, `dump_texture_v2`, and physical `.main` / `.alt` texture aliases in resource selectors, debug-bundle lists, and A/B capture recipes.
- Removed the obsolete v1 diagnostic control client and replaced the stdio integration dependency on the Java v1 fake server with a native strict-v2 gRPC fixture.
- `cmake --build --preset release --target vibris-action-schema-tests vibris-state-tests vibris-stdio-fixture-test vibris-stdio-v2-server vibris-mcp` passed with the Visual Studio CMake executable; direct `vibris-action-schema-tests.exe` printed `PASS ActionSchemaV2ToolContract`.
- `ctest --preset release -R 'ActionSchema|ToolMetadata|ResourceLists|Stdio' --output-on-failure` passed 5/5 tests: request-scoped metadata, empty resource lists, v2 action schema, stdio tool happy path, and stdio lifecycle.
- Direct stdio probe printed `PASS tools=8 schema_version=2 workspace_id=6e8e5769-4b9c-4aaf-9396-4fabc4ffd6d7 grpc_status=test-save request_scoped=true` and `CLEANUP owned_processes=4 temp_removed=True listener_closed=true`.
- `.\gradlew.bat :vibris-protocol-java:test --offline` passed with 23 actionable tasks and regenerated bindings only under build outputs.
- Commit: this task's commit with subject `T02 publish compact typed MCP v2 tools`.

### T02A — Restore a strict v2 Core compilation baseline

Status: `DONE`

Dependencies: T02

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\`
- `I:\code\vibris\core\src\test\`

Scope:

- Port maintained Core production code and tests directly from the removed `dev.vibris.protocol.v1` generated API to the strict `dev.vibris.protocol.v2` contract introduced by T01.
- Remove or rewrite Core constructs whose v1 wire types or fields no longer exist so the retained Core service, job execution, artifact, and test baseline compiles against generated v2 bindings.
- Preserve the existing runtime adapter API and protected concurrent runtime-adapter changes while establishing the buildable v2 baseline required by T03.

Non-scope:

- Do not regenerate, alias, shim, adapt, dual-read, or otherwise restore any v1 protocol surface.
- Do not implement T03 lease/status waiting behavior or later durable jobs, restoration, settings, compile validation, provenance, artifact-v2 lifecycle, or pass-boundary capture semantics.
- Do not modify or stage the protected `ThreadBoundVibrisRuntimeAdapter.kt` or `ThreadBoundVibrisRuntimeAdapterTest.kt` changes.

Acceptance:

- `core/src/main` and maintained Core tests contain no `dev.vibris.protocol.v1` references and compile only against generated strict-v2 bindings.
- The retained Core test suite passes without a v1 generated package, compatibility bridge, fallback schema, or dual protocol service.
- The protected runtime-adapter changes and `capture\a.spv` remain byte-for-byte outside task staging.

Verification:

- `rg -n "dev\\.vibris\\.protocol\\.v1|vibris\\.control\\.v1" core/src/main core/src/test`
- `.\gradlew.bat :vibris-protocol-java:test :vibris-core:test --offline`
- `git diff --cached --check` plus exact staged names/stat/full diff proving no protected file is staged.

Expected commit title: `T02A migrate Core directly to strict v2`

Blockers:

- None known. This remediation was inserted after T03's baseline verification proved that T01 removed the Java v1 generated package while 44 maintained Core source/test files still imported it; a package-only rewrite also exposed intentionally removed v1 Job, Action, Artifact, Error, and Status APIs that require a direct semantic v2 port.

Evidence:

- Discovery receipt: `.\gradlew.bat :vibris-core:test --offline` failed in `:vibris-core:compileKotlin` because `dev.vibris.protocol.v1` no longer exists after T01; `rg -l "dev\\.vibris\\.protocol\\.v1" core/src/main core/src/test` reported 44 files.
- A temporary package-only rewrite was fully reverted before this ledger control-plane commit; only the pre-existing protected files and untracked `capture\a.spv` remained dirty.
- Ported maintained Core production and tests directly to `dev.vibris.protocol.v2`: strict envelopes carry request/workspace identity, jobs use `JobSpec`, status/readiness/resources use v2 messages, action execution returns v2 `ActionReceipt` / `JobResult`, and artifact metadata uses the v2 identity/path fields.
- Removed the obsolete v1-only `BenchmarkCaseIsolation` baseline instead of retaining an alias or shim; later transactional restoration remains assigned to T05.
- `rg -n "dev\\.vibris\\.protocol\\.v1|vibris\\.control\\.v1" core/src/main core/src/test` returned no matches.
- `.\gradlew.bat :vibris-protocol-java:test :vibris-core:test --offline --console=plain` passed; the Core suite completed 83 tests with zero failures.
- Protected `ThreadBoundVibrisRuntimeAdapter.kt` and `ThreadBoundVibrisRuntimeAdapterTest.kt` stayed outside task staging with their pre-existing `11/4` and `8/6` numstat respectively; untracked `capture\a.spv` also stayed outside staging.
- Commit: this task's commit with subject `T02A migrate Core directly to strict v2`.

### T03 — Add truthful runtime lease and status waiting

Status: `DONE`

Dependencies: T02A

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\FairJobScheduler.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ServerDescriptor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\VibrisCoreEngine.kt`

Scope:

- Model the shared Minecraft/GPU owner as a service-managed lease with owner, request, worktree, operation, stage, timestamps, progress, ETA, cancellation state, and fair queue position.
- Expose separate `can_accept_job` and `can_start_job` plus MCP, Minecraft, world, scene, shader, and GPU-timing readiness.
- Add bounded last-error metadata, 32 transition records, recovery advice, and event-driven waits for readiness or job terminality.

Non-scope:

- Do not expose manual lease acquisition/release or use the removed `ready` alias.

Acceptance:

- Two workspace identities are scheduled fairly against one runtime and observe the same authoritative lease owner.
- Cancellation does not release the lease before a safe state.
- Status waits wake on matching transitions and return bounded timeout receipts without polling loops.

Verification:

- `.\gradlew.bat :vibris-core:test --offline`
- `cmake --build --preset release --target vibris-state-tests vibris-grpc-request-ownership-tests`
- `ctest --preset release -R "RequestScoped|Ownership|Status|Lease|Transition"`

Expected commit title: `T03 expose runtime lease and status transitions`

Blockers:

- None known.

Evidence:

- `FairJobScheduler` now publishes the single active runtime owner plus a deterministic cross-workspace round-robin queue; the regression fixture held workspace A's first lease, queued A then B, observed positions B=1/A=2, and executed A1/B1/A2.
- `ServerDescriptor` advertises strict-v2 `RUNTIME_LEASE` and `STATUS_WAIT`, separates `can_accept_job` from `can_start_job`, reports the authoritative owner/request/worktree/operation/stage/start time/cancellation state, and omits optional ETA when no truthful estimate exists.
- Core status now exposes bounded job summaries, recovery-bearing last-error metadata, and the newest 32 state/phase transitions; FULL/JOBS/SUMMARY detail levels do not invent unavailable GPU-timing readiness.
- Cancellation marks the active lease before runtime completion and does not release it until the blocked runtime future reaches a safe completion; synchronous adapter cancellation is normalized to a terminal v2 `CANCELLED` result.
- Both `CAN_START_JOB` and `JOB_TERMINAL` waits wake from monitor notifications on matching state transitions; a zero-duration occupied wait returns a bounded timeout receipt without a polling loop.
- `mcp/src/test/cpp/grpc_request_ownership_tests.cpp` was ported directly from removed v1 resume messages to strict-v2 `ResumeJob` and non-terminal `JobStateSnapshot` ownership semantics; no compatibility path was added.
- `mcp/src/test/cpp/state_tests.cpp` proves protobuf-to-MCP JSON preservation of lease ownership, cancellation, fair queue position, transition history, last-error recovery advice, and wait flags.
- `.\gradlew.bat :vibris-core:test --offline --console=plain` passed with 86 tests.
- Visual Studio CMake build passed for `vibris-state-tests` and `vibris-grpc-request-ownership-tests`; `ctest --preset release -R "RequestScoped|Ownership|Status|Lease|Transition"` passed 7/7 scenarios (run with process-local PowerShell execution-policy bypass so the existing `FakeServerStatus` script could execute).
- Protected `ThreadBoundVibrisRuntimeAdapter.kt` and `ThreadBoundVibrisRuntimeAdapterTest.kt` remained outside task staging with their pre-existing `11/4` and `8/6` numstat; untracked `capture\a.spv` also remained outside staging.
- Commit: this task's commit with subject `T03 expose runtime lease and status transitions`.

### T04 — Generalize durable resumable jobs

Status: `DONE`

Dependencies: T03

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\profile_matrix_workflow.cpp`
- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`
- `I:\code\vibris\mcp\src\main\cpp\pending_request_registry.cpp`

Scope:

- Replace the profile-matrix-specific workflow with a common `.vibris\jobs\<job_id>` engine containing immutable request/sources, atomic state, append-only events, partial receipts, and final result.
- Cover matrix, paired benchmark, compile validation, and other long-running starters with async start, query, result, cancel, and safe resume.
- Checkpoint every case and benchmark measurement/control/visual step; recover uncertain Core requests by query/resume without blind resubmission.

Non-scope:

- Do not read or migrate `.vibris\profile-matrix` checkpoints or replay uncertain side effects.

Acceptance:

- An interruption after case 17/38 resumes at case 18 with no duplicate completed receipt.
- MCP restart reconstructs jobs and monotonic event sequences.
- `resumable` and `cancelable` are truthful; ETA is null until enough evidence exists.

Verification:

- `cmake --build --preset release --target vibris-profile-matrix-workflow-tests vibris-job-protocol-tests`
- `ctest --preset release -R "Workflow|Checkpoint|Resume|JobProtocol"`
- Inspect a generated job directory for atomic state and append-only events.

Expected commit title: `T04 add durable resumable workflow jobs`

Blockers:

- None known.

Evidence:

- `DurableJobWorkflow` now owns the strict-v2 `.vibris\jobs\<job_id>` layout: immutable `request.json` and frozen `sources\`, atomic `state.json`, append-only `events.jsonl`, immutable per-step `receipts\NNNNNNNN.json`, and immutable terminal `result.json`; it never reads or migrates `.vibris\profile-matrix`.
- Profile matrices and action matrices are expanded into one durable step per selected source/config case; paired benchmarks use the canonical deterministic planner and checkpoint every comparison, same-source control, and optional visual step. Generic case arrays provide the same engine for later compile-validation and long-running starters.
- `vibris_run_recipe`, `vibris_run_actions`, and `vibris_run_matrix` accept durable async execution; `vibris_job` implements only strict-v2 query/result/cancel/resume operations. A cancelled job exposes partial receipts, and `resumable`, `cancelable`, and ETA are derived from safe request state, execution mode, and at least two observed step durations.
- Accepted Core requests retain their request identity across interruption and are resumed through strict-v2 `ResumeJob`; unaccepted requests may be safely submitted again. `SynchronousJobRunner` now applies this rule to generic recipes and matrices as well as profiles, with no blind accepted-side-effect replay.
- The durable restart fixture interrupted case 18 after 17 immutable receipts, reconstructed a new workflow instance, resumed `request-18`, completed the remaining 21 calls, and proved exactly 38 unique completed case identities plus a monotonic append-only event sequence.
- The fixture inspected the generated job directory while live and proved `request.json`, `state.json`, `events.jsonl`, `sources\`, 17 partial receipts before restart, 38 receipts after completion, unchanged immutable request bytes, and a published `result.json`. Matrix, compile-like generic cases, async cancel/resume, and 16 paired measurement/control plus one visual checkpoint were also covered.
- Visual Studio CMake `cmake --build --preset release --target vibris-profile-matrix-workflow-tests vibris-job-protocol-tests` passed; `ctest --preset release -R "Workflow|Checkpoint|Resume|JobProtocol"` passed 5/5. The MCP production target also built, and action-schema plus paired-benchmark regression coverage passed 6/6 (11/11 in the combined focused run).
- Protected `ThreadBoundVibrisRuntimeAdapter.kt` and `ThreadBoundVibrisRuntimeAdapterTest.kt` remained outside task staging with their pre-existing `11/4` and `8/6` numstat; untracked `capture\a.spv` also remained outside staging.
- Commit: this task's commit with subject `T04 add durable resumable workflow jobs`.

### T05 — Add transactional restoration and recovery

Status: `DONE`

Dependencies: T04

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\BenchmarkCaseIsolation.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\RuntimeJobExecutor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\VibrisControlService.kt`

Scope:

- Apply explicit `restore_state.on_success/on_error` transactions to all source/config/scene mutations; benchmark, matrix, and compile validation always restore.
- Snapshot and verify source, config/settings, scene, and temporal state on success, error, cancellation, and timeout.
- Add `recover_runtime` that revalidates the runtime link and reapplies the last safe snapshot without restarting Minecraft.

Non-scope:

- Do not force-release leases, restart processes, or claim recovery without a verification receipt.

Acceptance:

- Every terminal path returns a structured restoration receipt.
- Restore failure prevents new work and retains ownership until the runtime is safe or explicitly failed.
- Recovery either proves readiness or returns exact manual recovery instructions.

Verification:

- `.\gradlew.bat :vibris-core:test --offline`
- Run focused success, failure, cancel, timeout, restore-failure, and recovery tests.

Expected commit title: `T05 make runtime mutations transactional`

Blockers:

- None known.

Evidence:

- `BenchmarkCaseIsolation` now retains the Core-owned source plus immutable Core-observed settings and scene state before a mutating job. It enforces `restore_state.on_success/on_error`, forces restoration for matrix, compile-validation, and benchmark workloads, hashes expected and actual source/settings/scene identities, and emits a typed `RestorationReceipt` for successful, failed, cancelled, timed-out, rejected, and no-mutation terminal paths.
- `RuntimeJobExecutor` restores with `CancellationToken.none()` and an uninterruptible bounded wait, verifies the source identity/content, successful settings reload, exact returned scene, and temporal reset, then attaches the receipt to `JobResult` or `JobFailed`. The first safe snapshot is established only through an explicit non-restoring load, and the MCP contract exposes both restore booleans directly.
- A restore failure marks activation unsafe, retains both the prior snapshot reference and original job sources, preserves the original runtime lease ID in `RECOVERING`, rejects ordinary jobs, and never force-releases the held ownership. `recover_runtime` is idempotent when already safe and otherwise retries the retained snapshot; readiness is restored only after verification, while failures include exact no-restart manual recovery instructions and the failed receipt.
- The existing eight-tool MCP surface now exposes source-free `vibris_run_recipe { recipe: "recover_runtime" }` without a preset, encodes the dedicated v2 recovery workload, skips source preparation, and preserves failed restoration receipts in structured tool error details. Scene recipes still require `preset_id`, and recovery rejects unrelated fields.
- `\.\gradlew.bat :vibris-core:test --offline --console=plain` passed 95/95. The focused `RuntimeRestorationTest` plus engine ownership test passed 9/9 and covers success, temporal-only restoration, ordinary failure, cancellation, execution timeout, restore failure, repeated recovery failure/success, idempotent recovery, lease retention, admission blocking, and readiness release.
- Visual Studio CMake built `vibris-action-schema-tests`, `vibris-job-protocol-tests`, and the production `vibris-mcp` target. `ctest --preset release -R "ActionSchema|JobProtocol"` passed 2/2.
- No deploy or process restart occurred. Protected `ThreadBoundVibrisRuntimeAdapter.kt` and `ThreadBoundVibrisRuntimeAdapterTest.kt` remained outside task staging at their pre-existing `11/4` and `8/6` numstat; untracked `capture\a.spv` also remained untouched.
- Commit: this task's commit with subject `T05 make runtime mutations transactional`.

### T06 — Define effective shader settings contract

Status: `DONE`

Dependencies: T05

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\ReloadResult.kt`
- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\VibrisRuntimeAdapter.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\BenchmarkProvenance.kt`

Scope:

- Define resolved setting values, defaults, changed-from-default diff, origin (`default`, `preserved_current`, `request_override`, `preset`), and a stable settings hash.
- Require successful reloads to return complete effective settings and propagate them into state snapshots and provenance.

Non-scope:

- Do not keep nullable/unknown successful-setting fields or infer settings in MCP from request input.

Acceptance:

- Contract tests reject successful reload results without complete settings.
- Preserve and override cases produce deterministic origin maps and hashes.

Verification:

- `.\gradlew.bat :vibris-api:test :vibris-core:test --offline`

Expected commit title: `T06 expose resolved shader settings contract`

Blockers:

- None known.

Evidence:

- Added the immutable `EffectiveShaderSettings` API contract with canonical name ordering, per-setting resolved value,
  default value, changed-from-default derivation, and exhaustive `DEFAULT`, `PRESERVED_CURRENT`, `REQUEST_OVERRIDE`,
  and `PRESET` origins. Duplicate or blank names and caller-supplied non-canonical hashes are rejected.
- Defined a domain-separated, length-prefixed SHA-256 over the complete ordered name/value/default state. Origin is
  intentionally excluded from state identity, so preserve, preset, and explicit override paths that resolve to the
  same settings produce the same restoration hash while retaining different audit origins.
- Removed the source-free success factory: every successful `ReloadResult` now requires a non-null complete effective
  settings snapshot. Core stores only the runtime-returned snapshot for preserve and override reloads, emits its full
  settings/default/diff/origin/hash in runtime-mutation receipts, and never derives effective state from request input.
- Transaction snapshots and restoration receipts now carry the effective settings hash. Restoration reapplies the
  returned resolved values, accepts an origin change only when name/value/default state is identical, and verifies the
  runtime-returned post-restore snapshot before releasing ownership.
- `2026-08-11`: `.\gradlew.bat :vibris-api:test :vibris-core:test --offline` passed with API 6/6 and Core 95/95
  tests, zero failures, errors, or skips. Contract coverage rejects missing/invalid successful settings, proves
  deterministic preserve/override origins and hashes, proves receipts use runtime values rather than request values,
  and proves transactional restore replays the runtime-resolved baseline.

### T07 — Implement effective settings in Iris host

Status: `DONE`

Dependencies: T06

Repository: `I:\code\Iris`

Worktree: `I:\code\Iris`

Branch: `1.21.11-shaderdev`

Primary files:

- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\vibris\MinecraftVibrisRuntimeHost.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\shaderpack\option\`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\Iris.java`

Scope:

- Snapshot actual option values before preserve reloads and return the complete post-reload settings/defaults/origins/hash through the v2 runtime contract.
- Verify the active shader pack and settings only after reload completion.
- Commit Iris implementation first; on the next continuation record its full SHA in this ledger and make the owner-repository receipt commit.

Non-scope:

- Do not change general OptiFine option compatibility semantics or stage protected Iris runtime directories.

Acceptance:

- Preserve reports actual pre-existing values rather than null/unknown.
- Override and preset sources are distinguished deterministically.
- Iris build passes against the Vibris v2 composite dependency, and the full external SHA is recorded here.

Verification:

- `.\gradlew.bat :common:compileJava :fabric:build --offline`
- Focused option-value tests or a deterministic host test fixture.
- Verify protected Iris paths remain untracked and unstaged.

Expected commit title: `T07 report effective shader settings from Iris`

Blockers:

- None known.

Evidence:

- Iris external implementation commit: `7096295b3875a13b6f00607b6f30d0649bd4f68f` with subject
  `T07 report effective shader settings from Iris`; the commit is the current `1.21.11-shaderdev` HEAD and an
  ancestor of that branch.
- Preserve reloads snapshot every resolved option from the active `vibris` pack before loading. Successful reloads
  capture the complete actual post-install option/default state only after the replacement pipeline is installed,
  with deterministic origin precedence `request_override`, `preserved_current`, `preset`, then `default`.
- `2026-08-11`: `.\gradlew.bat :fabric:vibrisBridgeTest --offline --console=plain` passed 10/10 tests with zero
  failures, errors, or skips; `IrisVibrisEffectiveSettingsTest` passed 1/1 and proves complete ordered values,
  changed-from-default flags, deterministic override/preset/preserve origins, equal state hashes across origin-only
  changes, and identical resolved-state comparison.
- `2026-08-11`: `.\gradlew.bat :common:compileJava :fabric:build --offline --console=plain` passed with 41
  actionable tasks against the strict-v2 Vibris composite dependency.
- Iris protected `IrisVibrisLifecycle.java` remained outside the external commit and at its pre-existing `18/4`
  numstat; `.codex\`, `.vibris\`, and `common\logs\` remained untracked and unstaged. No deployment or process
  restart occurred.

### T08 — Return one ordered receipt per action

Status: `DONE`

Dependencies: T07

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ActionJobExecutor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\CaptureJobExecutor.kt`
- `I:\code\vibris\mcp\src\main\cpp\result_mapper.cpp`

Scope:

- Emit exactly one ordered receipt per input action, retaining original indices through grouped captures.
- Define complete wait, reset, activate, load, capture, compare, and failure receipts.
- Keep screenshot-internal waits inside the screenshot receipt and matrix auto-loads in `prelude_receipts`.

Non-scope:

- Do not expose synthetic action indices or duplicate grouped readback payloads.

Acceptance:

- Receipt count equals input action count for mixed and partially failing sequences.
- Indices are contiguous and ordered; every receipt has an explicit terminal status.

Verification:

- `.\gradlew.bat :vibris-core:test --offline`
- `cmake --build --preset release --target vibris-job-protocol-tests`
- `ctest --preset release -R "Action|JobProtocol"`

Expected commit title: `T08 return complete ordered action receipts`

Blockers:

- None known.

Evidence:

- Added a strict receipt book over the wire action sequence. It assigns separate contiguous indices to generated
  preludes and user actions, requires every slot to reach `OK`, `FAILED`, or `CANCELLED`, and preserves completed
  receipts plus explicit failure/cancellation errors when a later action fails.
- Grouped screenshot, texture, and buffer readbacks still execute as one runtime capture while returning one receipt
  per original action with only that action's resource and artifacts. Screenshot frame delays are represented by an
  `internal_wait` in the screenshot receipt and no synthetic wait action or index is exposed.
- Load/activate receipts now include source UUID/hash, complete effective settings, scene hash, and completion time;
  waits include frame bounds and completed counts; reset, patched-shader, compare, and failure paths now return typed
  terminal receipts. Failed terminals carry both `action_receipts` and `prelude_receipts` through Core and MCP.
- Recipe- and matrix-generated shader loads are marked as preludes. Contract tests prove one prelude receipt plus
  contiguous user-action receipts and preserve both receipt lists in failed MCP terminal details.
- `2026-08-11`: `.\gradlew.bat :vibris-protocol-java:test :vibris-core:test --offline --console=plain` passed with
  protocol Java 5/5 and Core 97/97 tests; focused action/capture coverage passed 13/13, all with zero failures, errors,
  or skips. The native `vibris-job-protocol-tests` target built successfully and
  `ctest --preset release -R 'Action|JobProtocol' --output-on-failure` passed 2/2 tests.
- Vibris protected adapter files remained at their pre-existing `11/4` and `8/6` numstats, `capture/a.spv` remained
  untracked and unread, and no deployment or process restart occurred.
- Commit: this task's commit with subject `T08 return complete ordered action receipts`.

### T09 — Define compile catalog runtime contract

Status: `DONE`

Dependencies: T08

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\VibrisRuntimeAdapter.kt`
- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\`

Scope:

- Define canonical program/pass compile entries, shader stages, compile/link states, patched-source identity, diagnostics, and mapping hash.
- Add runtime query and deterministic diagnostic fingerprint support.

Non-scope:

- Do not infer a per-program catalog from the existing global error string or implement the MCP recipe yet.

Acceptance:

- Contract tests cover graphics, compute, missing, compile-failed, and link-failed entries.
- Diagnostic fingerprints are stable across ordering changes.

Verification:

- `.\gradlew.bat :vibris-api:test :vibris-core:test --offline`

Expected commit title: `T09 define compile validation catalog contract`

Blockers:

- None known. The protected adapter/test changes were committed by the user as
  `b7a0931d85042442c0360a38c50e30d811be9486`, resolving the prior runtime-query ownership conflict without a fallback
  or compatibility path.

Evidence:

- `2026-08-11`: the continuation re-read the complete ledger and verified Vibris `main` at
  `7084bf440e2cc97f066a2fd03893954e2824e94d`, Iris `1.21.11-shaderdev` at
  `7096295b3875a13b6f00607b6f30d0649bd4f68f`, every declared worktree, and empty staging areas.
- The protected adapter and its test remain at their recorded `11/4` and `8/6` numstats. Source inspection proved
  that `ThreadBoundVibrisRuntimeAdapter` is the sole production bridge from the public runtime adapter to
  `VibrisRuntimeHost`; therefore an operational compile-catalog query cannot be added without overlapping the
  protected file.
- No product source, protected file, deployment, or process state was changed. T10 remains `PENDING`.
- `2026-08-11`: blocker-resolution audit verified Vibris `main` at
  `b7a0931d85042442c0360a38c50e30d811be9486` with only protected untracked `capture/a.spv`, and Iris
  `1.21.11-shaderdev` at `6322cb2833edfddbfa64d0ac6001988c4d49efd1` with only the recorded runtime directories.
  All declared auxiliary worktrees remain clean at their recorded HEADs; T09 is safe to resume as the sole `READY`
  task after this control-plane commit.
- Added immutable `CompileCatalog` API records for uniquely ordered program/pass entries, graphics and compute stages,
  terminal compile/link states, patched-source SHA-256 identities, shader generation, and a domain-separated mapping
  hash derived only from the canonical intended mapping.
- Added domain-separated diagnostic fingerprints over severity, file, line, column, and message. Catalog factories sort
  entries and diagnostics without making fingerprints depend on discovery/log ordering or artifact log paths, while
  canonical constructors reject duplicate identities, invalid state combinations, and mismatched hashes.
- Replaced the string `RuntimeAction.InspectShader` route with a required cancellable runtime catalog query through
  `VibrisRuntimeAdapter`, `ThreadBoundVibrisRuntimeAdapter`, and `VibrisRuntimeHost`. `inspect_shader` is now a
  dedicated execution step returning `ShaderInspectionReceipt`; load synchronization also consumes the typed query
  directly and never parses or preserves the old global error string.
- Contract coverage includes graphics, compute, missing, compile-failed, and link-failed entries, stable identities
  under reordered inputs, every API record boundary, lossless protobuf mapping, typed action receipts, client-thread
  routing, and cancellation/restoration behavior. `RuntimeAction.InspectShader` references are absent repository-wide.
- `2026-08-11`: `.\gradlew.bat :vibris-api:test :vibris-core:test :vibris-capture:compileKotlin --offline` passed with
  API 7/7 and Core 101/101 tests and zero failures or errors; capture action compilation passed after removal of the old
  sealed-action branch. A broader integration compile remains independently blocked before integration compilation by
  the pre-existing `test-runtime` v1 probe references assigned to T18; no compatibility remediation was pulled forward.
- The protected untracked `capture/a.spv` remained unread and unstaged. No deployment or process restart occurred.
- Commit: this task's commit with subject `T09 define compile validation catalog contract`.

### T10 — Emit complete Iris compile catalog

Status: `DONE`

Dependencies: T09

Repository: `I:\code\Iris`

Worktree: `I:\code\Iris`

Branch: `1.21.11-shaderdev`

Primary files:

- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\pipeline\IrisRenderingPipeline.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\vibris\MinecraftVibrisRuntimeHost.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\shaderpack\programs\`

Scope:

- Build the intended program/pass catalog during pipeline creation and retain compile/link diagnostics and patched-source identities.
- Expose the catalog only after the current pipeline/reload is complete.
- Commit Iris implementation first; record the external full SHA in a later Vibris ledger-only receipt commit.

Non-scope:

- Do not change shader discovery order or treat compiler success as proof of runtime rendering correctness.

Acceptance:

- Catalog enumerates intended begin/prepare/deferred/composite/final/shadow and compute programs, including failures.
- Mapping hash is deterministic for equivalent program sets.
- Iris build passes and the external full SHA is recorded here.

Verification:

- `.\gradlew.bat :common:compileJava :fabric:build --offline`
- Focused catalog construction tests or deterministic pipeline fixture.

Expected commit title: `T10 emit Iris program compile catalog`

Blockers:

- None known.

Evidence:

- Iris external implementation commit: `0ccdadd3a9b80891d147ace95a3c3919b7055b76` with subject
  `T10 emit Iris program compile catalog`; the commit is the current `1.21.11-shaderdev` HEAD, its parent is the
  previously recorded Iris HEAD `6322cb2833edfddbfa64d0ac6001988c4d49efd1`, and it is an ancestor of the branch.
- Pipeline construction registers complete intended begin, prepare, deferred, composite, final, shadow-composite,
  setup/shadow/final compute, and resolved gbuffer/shadow program variants before compilation can abort. Entries retain
  terminal compile/link state, canonical stages, structured diagnostics, and a domain-separated patched-source SHA-256;
  equivalent mappings are canonicalized independently of discovery/registration order.
- The runtime host publishes the immutable catalog only after pipeline construction and the reload install/restore path
  has completed. Failed or unattempted intended entries remain explicit, compiler/linker failures fail closed, and no
  shader discovery order, rendering-correctness claim, compatibility route, deployment, or process restart was added.
- `2026-08-11`: `.\gradlew.bat :fabric:vibrisBridgeTest :common:compileJava :fabric:build --offline
  --console=plain` passed with 43 actionable tasks. Bridge results contain 11/11 tests with zero failures, errors, or
  skips; `IrisVibrisCompileCatalogTest` passed 1/1 and proves complete missing/success/failure terminal states,
  deterministic mapping hashes across registration order, stable patched-source identity, and diagnostic locations.
- Iris protected `.codex\`, `.vibris\`, and `common\logs\` remained untracked and unstaged. Vibris protected
  `capture/a.spv` remained unread and unstaged. This owner-repository receipt is ledger-only.

### T11 — Add compile_validate recipe

Status: `DONE`

Dependencies: T10

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\synchronous_job_runner.cpp`
- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`
- `I:\code\vibris\mcp\src\main\cpp\tool_registry.cpp`

Scope:

- Add typed synchronous/asynchronous `compile_validate` for one source/config or a matrix, with optional baseline.
- Wait only for safe reload/compile completion, return every program/pass entry, compare added/resolved/unchanged diagnostics, persist results, and always restore runtime state.

Non-scope:

- Do not run render warmup, GPU timing samples, or accept global-error-only success.

Acceptance:

- Matrix cases are independently checkpointed and return complete provenance.
- Baseline diagnostic diff is stable and compile failures fail closed.

Verification:

- `cmake --build --preset release --target vibris-job-protocol-tests vibris-action-schema-tests`
- `ctest --preset release -R "Compile|JobProtocol|ActionSchema"`

Expected commit title: `T11 add compile validation recipe`

Blockers:

- None known.

Evidence:

- Added a strict typed `compile_validate` recipe for one source/config or a selected source/config matrix, with an
  optional independently prepared baseline/config. Compile jobs submit the protocol `CompileValidationRequest`
  workload directly and never synthesize render actions, warmup, or GPU sampling.
- Core reloads each case, queries the complete canonical compile catalog even when shader compilation fails, computes
  deterministic fingerprint-sorted added/resolved/unchanged diagnostic sets, returns per-case provenance, fails closed
  on incomplete baseline/global reload state, and uses the forced transactional restoration receipt.
- Both synchronous and asynchronous compile validation run through the durable workflow. Matrix selections become one
  immutable checkpoint per case under `.vibris/jobs`, and final results aggregate every case, artifact, pass/fail count,
  catalog, diagnostic diff, and provenance without duplicating completed steps.
- `cmake --build --preset release --target vibris-job-protocol-tests vibris-action-schema-tests
  vibris-profile-matrix-workflow-tests` passed. `ctest --preset release -R "Compile|JobProtocol|ActionSchema"` passed
  3/3: strict typed protocol mapping, recipe schema rejection/acceptance, and compile-matrix checkpoint aggregation.
- `.\gradlew.bat :vibris-core:test --tests dev.vibris.core.RuntimeJobExecutorTest --console=plain` passed 10/10,
  including complete catalog/diff/restoration without frame waits and fail-closed baseline compilation.
- Protected `capture/a.spv` remained untracked and unstaged; no deployment or runtime restart occurred.

### T12 — Expand immutable benchmark provenance

Status: `DONE`

Dependencies: T11

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\BenchmarkProvenance.kt`
- `I:\code\vibris\mcp\src\main\cpp\source_preparer.cpp`
- `I:\code\vibris\mcp\src\main\cpp\result_mapper.cpp`

Scope:

- Record branch/worktree/workspace, requested and resolved revisions, start/end HEAD, shader tree object, dirty shader delta, source snapshot/UUID, settings/config/preset/scene, load time, environment versions, GPU/GL/driver, and program/pass mapping hash.
- Recompute the shader fingerprint at completion and distinguish `head_changed` from shader-content `stale`.

Non-scope:

- Do not mark results stale merely because commit metadata changed while measured shader content remained identical.

Acceptance:

- Commit-message-only changes yield `head_changed=true, stale=false`.
- Tracked, untracked, or deleted shader changes yield `stale=true` with a deterministic delta hash.
- Every compile/performance/visual receipt proves the active source UUID.

Verification:

- `.\gradlew.bat :vibris-core:test --offline`
- `cmake --build --preset release --target vibris-workspace-source-tests vibris-job-protocol-tests`
- Run provenance fixtures for clean, metadata-only, tracked, untracked, and deletion cases.

Expected commit title: `T12 expand benchmark provenance and stale checks`

Blockers:

- None known.

Evidence:

- Extended strict-v2 prepared-source and result provenance with worktree/branch/workspace identity, requested and
  resolved revisions, start/completion HEAD, committed shader tree object, deterministic dirty shader delta,
  immutable source snapshot and active UUID, effective config/settings, preset/scene hashes, shader load time,
  runtime versions plus GPU/GL/driver strings, and the current program/pass mapping hash. Durable source checkpoints
  retain the same immutable fields across materialization and restart.
- MCP now recomputes the live workspace shader fingerprint at terminal mapping. Commit-message-only movement reports
  `head_changed=true, stale=false`; tracked edits, untracked additions, and deletions report `stale=true` with the same
  deterministic content-delta hash on repeated reads. Immutable commit-source results track completion HEAD without
  treating mutable workspace content as their measured source.
- Top-level action/performance/visual results and every compile-validation case carry the exact active source UUID;
  Core captures Java/OS/Vibris metadata directly and refreshes Minecraft/Iris/GPU/OpenGL/driver identity on the
  render thread when the real host is present.
- `\.\gradlew.bat :vibris-core:test --offline --console=plain` passed: 103 tests, 0 failures.
- Native release targets `vibris-workspace-source-tests`, `vibris-job-protocol-tests`, and the affected
  `vibris-profile-matrix-workflow-tests` built successfully. Focused CTest passed 7/7 provenance/JobProtocol cases;
  the broader workspace-source plus durable matrix/compile checkpoint regression passed 14/14.
- Protected untracked `capture\a.spv` remained outside task staging. Commit: this task's commit with subject
  `T12 expand benchmark provenance and stale checks`.

### T12A — Normalize strict-v2 execution receipts

Status: `DONE`

Dependencies: T12

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\synchronous_job_runner.cpp`
- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`
- `I:\code\vibris\mcp\src\test\cpp\synchronous_job_fixture.hpp`
- Focused strict-v2 result-normalization tests under `I:\code\vibris\mcp\src\test\cpp\`

Scope:

- Replace the native profile, matrix, and A/B visual projection's assumptions about removed result-kind,
  `action_results`, manifest-path, and frame-id fields with the canonical strict-v2 `JobResult` envelope,
  `action_receipts`, `prelude_receipts`, typed receipt details, top-level provenance, restoration, artifacts, and
  result-manifest identity.
- Normalize typed GPU metrics, compile-catalog inspection, runtime mutation, comparison, provenance, and restoration
  exactly once for downstream profile and paired-benchmark consumers.
- Replace stale native fake-server fixtures with current v2 messages and add focused execution-path coverage.

Non-scope:

- Do not implement T13 metric roles, statistical calculations, benchmark decisions, or guardrail thresholds.
- Do not accept the removed result shape through fallback, alias, dual-read, or compatibility branches.

Acceptance:

- Production native execution no longer reads removed raw `JobResult.action_results`, nested provenance, or a
  synthetic provenance `complete` flag.
- Profile, matrix, and visual results consume only strict-v2 typed receipts and preserve one ordered normalized
  receipt, complete top-level provenance, and the authoritative restoration receipt without duplication.
- Current-v2 fixtures pass, and an old result shape is rejected instead of translated.

Verification:

- `cmake --build --preset release --target vibris-synchronous-job-runner-tests vibris-job-protocol-tests`
- `ctest --preset release -R "StrictV2Result|ProfileNormalization|MatrixNormalization|JobProtocol"`
- Static search proving production result consumers contain no removed raw-field reads or synthetic compatibility
  branches.

Expected commit title: `T12A normalize strict v2 execution receipts`

Blockers:

- None known. This remediation was inserted when T13 entry auditing proved the current protocol exposes
  `JobResult.action_receipts`, top-level `ResultProvenance`, and `RestorationReceipt`, while production normalization
  still dereferences raw `action_results`, requires a nonexistent provenance `complete` field, and the native fake
  server still constructs removed v1 result fields.

Evidence:

- Discovery receipt: `proto/vibris_control.proto` declares `JobResult.provenance`, `action_receipts`,
  `prelude_receipts`, and `restoration`; `synchronous_job_runner.cpp` still reads `job.at("action_results")` at two
  execution paths and requires `provenance.value("complete", false)`; `synchronous_job_fixture.hpp` still calls
  removed `set_kind`, `set_manifest_path`, `add_frame_ids`, and `add_action_results` methods.
- T13 product files, runtime state, deployment, and processes were not changed. Protected `capture\a.spv` remained
  untracked and unread.
- Control-plane commit: this ledger-only insertion with subject
  `roadmap insert T12A strict v2 result remediation`.
- Native profile, matrix, inspection, and A/B visual projections now accept only the strict-v2 `JobResult` envelope,
  consume typed prelude/action receipt details, normalize GPU metrics once, and retain top-level provenance,
  restoration, artifacts, timings, and result-manifest identity. Paired benchmark consumers use only this normalized
  surface, including the internal visual gate.
- The native fake-server fixture was replaced by compact current-v2 terminal-message builders. Focused coverage proves
  typed runtime mutation, compile-catalog inspection, GPU metrics, matrix cases, capture/comparison details, strict
  artifacts, provenance, and restoration; an `action_results` result is rejected rather than translated.
- Release targets `vibris-synchronous-job-runner-tests`, `vibris-job-protocol-tests`,
  `vibris-paired-benchmark-tests`, and `vibris-profile-matrix-workflow-tests` built successfully. The ledger-specified
  CTest filter passed 4/4; visual, paired, noise-floor, and durable checkpoint regression passed 9/9.
- Static production searches found zero reads of removed `action_results`, manifest-path, benchmark-barrier, or
  synthetic provenance `complete` fields, and zero legacy/fallback/dual-read/compatibility branches in the affected
  consumers. No deployment or process restart occurred; protected `capture\a.spv` remained untracked and unread.
- Commit: this task's commit with subject `T12A normalize strict v2 execution receipts`.

### T13 — Enforce statistical benchmark guardrails

Status: `DONE`

Dependencies: T12A

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\paired_benchmark.cpp`
- `I:\code\vibris\mcp\src\main\cpp\native_metrics.cpp`
- `I:\code\vibris\mcp\src\test\cpp\paired_benchmark_tests.cpp`

Scope:

- Add typed target, sibling, and sentinel metrics with explicit guardrails.
- Preserve ABBA/ABAB/randomized order and A/A controls while reporting paired delta, p50/p95, confidence interval, noise floor, order effect, direction reversal, and thermal/temporal drift.
- Make compile, provenance, restoration, visual, and statistical results mandatory gates for acceptance.

Non-scope:

- Do not declare a win from a single average or a change inside measured noise.

Acceptance:

- Stable target regression rejects; below-noise improvement or direction reversal is `INCONCLUSIVE`.
- Stable sibling/sentinel guardrail regression rejects.
- Only a stable target improvement with every other gate passing is accepted.

Verification:

- `cmake --build --preset release --target vibris-paired-benchmark-tests`
- `ctest --preset release -R "Paired|Noise|Order|Guardrail|Visual"`

Expected commit title: `T13 enforce benchmark semantic guardrails`

Blockers:

- None known.

Evidence:

- `benchmark_ab` now accepts only unique typed target/sibling/sentinel metrics. Target metrics use paired p50 deltas,
  p95 reporting, Student-t confidence, measured same-source p95 noise floors, order effects, direction reversal,
  outliers, and thermal/temporal drift; sibling and sentinel metrics require explicit maximum regression ratios.
- Every nested measurement performs a canonical compile-catalog inspection. Acceptance requires complete compile,
  provenance, restoration, deterministic visual-threshold, and statistical gates; stable target or guardrail
  regressions return `REGRESSION`, unstable/below-noise evidence returns `INCONCLUSIVE`, and missing gates return
  `GATE_FAILED`. The removed single-statistic/metric-filter benchmark shape is rejected rather than translated.
- Release targets `vibris-paired-benchmark-tests` and `vibris-action-schema-tests` built successfully. The
  ledger-specified paired/noise/order/guardrail/visual filter plus the typed action-schema contract passed 12/12,
  covering ABBA/ABAB/randomized planning, target/sibling/sentinel decisions, unchanged sentinels, noise, confidence,
  reversal, drift, compile, provenance, restoration, and visual failures. Protected `capture\a.spv` remained
  untracked and unstaged; no deployment or process restart occurred.

### T14 — Replace artifacts with managed v2 manifests

Status: `DONE`

Dependencies: T13

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ArtifactManager.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ArtifactManifest.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ServerConfiguration.kt`

Scope:

- Replace config and manifests with strict schema v2, default 168-hour TTL, per-workspace/job/request grouping, file roles, sizes, SHA-256, expiry, and totals.
- Implement startup/periodic/pre-reservation expiration, list/detail/capacity, and deletion guarded by exact ID plus expected manifest SHA.
- Add best-effort start estimate and authoritative post-load pre-readback reservation; warn at 80 percent and fail before readback when capacity cannot fit.

Non-scope:

- Do not parse, index, migrate, or auto-delete v1 configs/manifests/artifacts.

Acceptance:

- Missing config gets v2 defaults; any non-v2 config fails explicitly.
- Expired artifacts remain in the durable job ledger as `expired=true` metadata.
- User-visible paths are confined to the request worktree `.vibris\artifact` directory.
- Delete races and workspace ownership violations are rejected.

Verification:

- `.\gradlew.bat :vibris-core:test --offline`
- Run focused TTL, hash, quota, reservation, ownership, deletion-race, and unsupported-version tests.

Expected commit title: `T14 add managed artifact v2 lifecycle`

Blockers:

- None known.

Evidence:

- Replaced the artifact store directly with strict schema v2: missing `server.json` now writes canonical v2 defaults,
  non-v2 configuration fails with `UNSUPPORTED_VERSION`, TTL defaults to 168 hours, and the artifact manager indexes
  only workspace/job/request-grouped v2 manifests carrying typed roles/formats, per-file sizes and SHA-256, created and
  expiry times, inclusive totals, and an external content hash for guarded deletion. The removed v1 recovery,
  unreported-result protection, quota eviction, and terminal-delivery compatibility paths were not retained.
- Startup, status-periodic, list/detail, and pre-reservation expiration now delete only indexed expired v2 groups;
  non-v2 artifact data remains untouched and unindexed. Capacity reports used/reserved/estimated bytes, warns at 80
  percent, never evicts a live manifest, and capture admission reserves catalog-derived readback bytes plus a bounded
  manifest envelope before GPU readback. Exact manifest ID/SHA and workspace ownership are revalidated at deletion.
- Added the strict-v2 `ManageArtifacts` Core RPC and wired `vibris_artifacts` list/get/capacity/delete through MCP.
  Returned files and manifests accept only the v2 workspace/job/request layout before rewriting beneath the explicit
  worktree `.vibris\artifact` link. Durable job result reads preserve missing artifact entries with `expired=true`.
- `\.\gradlew.bat :vibris-core:test --offline` passed 94/94 tests with focused default-config,
  unsupported-version, TTL/startup expiration, v1 preservation, canonical hash/total, reservation/quota warning,
  ownership, and deletion-race coverage. The release `vibris-mcp` target linked successfully; strict-v2 protocol,
  action-schema, and durable-workflow/expired-artifact native executables passed 3/3. A broader non-gating
  `mcp-tests` aggregate build still encounters pre-existing removed-v1 reconnect/source fixture and obsolete
  `PreparedSourceRef.uuid` compile failures outside T14; T14 production targets and focused checks compile cleanly.
- Protected `capture\a.spv` remained untracked and unstaged. No deployment, Minecraft/launcher restart, or generated
  delivery refresh occurred.

### T15 — Define named pass resource dump contract

Status: `DONE`

Dependencies: T14

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\CapturePlan.kt`
- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\ResourceCatalog.kt`
- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\VibrisRuntimeAdapter.kt`

Scope:

- Define canonical `stage/program` pass descriptors, mapping SHA, logical texture views, buffer resources, pending boundary requests, and after-pass receipts.
- Add `dump_texture_after_pass` and `dump_buffer_after_pass`; unify immediate dumps on the same canonical resource selector.

Non-scope:

- Do not accept fuzzy pass names, `.main/.alt` resource suffixes, buffer views, or high-frequency draw stages.

Acceptance:

- Schema validates texture `current|alternate|main|alt`, mip/layer/format, and full-BIN buffer requests.
- Unknown or ambiguous pass/resource requests fail before scheduling.

Verification:

- `.\gradlew.bat :vibris-api:test :vibris-core:test --offline`
- `cmake --build --preset release --target vibris-action-schema-tests`
- `ctest --preset release -R "ActionSchema|PassResource"`

Expected commit title: `T15 define named pass resource dump contract`

Blockers:

- None known.

Evidence:

- Added one canonical resource selector across immediate and after-pass texture/buffer dumps. Texture requests require an
  explicit `current|alternate|main|alt` view plus bounded mip/layer and `png|bin`; buffer requests expose only a logical
  resource and always capture complete BIN bytes. The strict v2 protobuf and MCP JSON schema contain no old selector,
  buffer field, physical suffix, or implicit-view compatibility path.
- `ResourceCatalog` now publishes uniquely ordered logical resources, available texture views, exact canonical
  `stage/program` pass descriptors, readable resources, and a verified SHA-256 of the complete pass/resource mapping.
  `CapturePlan` carries mapping-bound one-shot pending requests and exact after-pass receipts, while the runtime adapter
  and host expose the asynchronous boundary seam required by T16/T17.
- Core planning resolves exact pass/resource/view identities and expands artifact outputs before scheduling. Unknown
  passes/resources, unavailable views, ambiguous catalog identities, fuzzy pass IDs, `.main/.alt` names, buffer views,
  unspecified texture views, invalid subresources, and non-BIN buffer shapes fail closed.
- `.\gradlew.bat :vibris-api:test :vibris-core:test --offline` passed 104/104 tests (API 8/8, Core 96/96).
  The supporting clean-cutover check `.\gradlew.bat :vibris-capture:test --offline` passed 26/26 tests.
- The release `vibris-action-schema-tests` target built successfully and
  `ctest --preset release -R "ActionSchema|PassResource"` passed 1/1. The additional strict protocol conversion target
  and `JobProtocol` CTest passed 1/1, including exact texture/buffer after-pass protobuf fields.
- Protected `capture\a.spv` remained untracked and unstaged. No deployment, Minecraft/launcher restart, or generated
  delivery refresh occurred.

### T16 — Implement named Iris pass boundary hooks

Status: `DONE`

Dependencies: T15

Repository: `I:\code\Iris`

Worktree: `I:\code\Iris`

Branch: `1.21.11-shaderdev`

Primary files:

- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\pipeline\CompositeRenderer.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\pipeline\FinalPassRenderer.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\shadows\ShadowCompositeRenderer.java`

Scope:

- Register ordered begin/prepare/deferred/composite/final/shadow-composite pass descriptors during pipeline construction.
- Invoke one-shot boundary capture only after all compute and graphics work, with required memory barriers and exact render-target flip snapshots.
- Capture texture or SSBO bytes into owned CPU memory while restoring all touched GL state; keep PNG encoding and artifact writes off the render thread where possible.
- Commit Iris implementation first; record the external full SHA in a later Vibris ledger-only receipt commit.

Non-scope:

- Do not instrument gbuffer, terrain, ordinary shadow draws, or modify shader debug routing.

Acceptance:

- `current` selects the next-stage readable side; alternate and physical views are deterministic.
- Final capture runs after the final render pass closes; shadow composite follows identical semantics.
- Unknown/cancelled/timed-out requests leave no hook; captures add no GL errors or binding leaks.
- Iris build passes and the external full SHA is recorded here.

Verification:

- `.\gradlew.bat :common:compileJava :fabric:build --offline`
- Focused pass-order, flip-state, cancellation, and host contract tests.
- Live GL verification is deferred to T20.

Expected commit title: `T16 capture resources after named Iris passes`

Blockers:

- None known.

Evidence:

- Iris external implementation commit: `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9` with subject
  `T16 capture resources after named Iris passes`; the commit is the current `1.21.11-shaderdev` HEAD, its parent is
  the previously recorded Iris HEAD `0ccdadd3a9b80891d147ace95a3c3919b7055b76`, and it is an ancestor of the branch.
- Pipeline construction registers uniquely ordered begin, prepare, deferred, composite, final, and shadow-composite
  descriptors. Each pass retains its exact post-pass flip snapshot; `current` selects the next-stage-readable side,
  `alternate` selects its opposite, and `main` / `alt` resolve deterministic physical textures without accepting
  physical-name suffixes in the logical selector.
- One-shot requests execute only after compute barriers and after each graphics `RenderPass` closes. Texture and SSBO
  readbacks move into owned CPU memory while pixel-pack and buffer bindings remain restored; PNG encoding and artifact
  writes run off the render thread. Unknown, cancelled, timed-out, completed, and destroyed-pipeline requests leave no
  pending boundary registration, and no gbuffer, terrain, ordinary-shadow, shader-debug-routing, fallback, alias, or
  compatibility path was added.
- `2026-08-11`: `.\gradlew.bat :fabric:vibrisBridgeTest :common:compileJava :fabric:build --offline
  --console=plain` passed with 43 actionable tasks. Bridge results contain 15/15 tests with zero failures, errors, or
  skips; focused coverage proves pass order, main/shadow flip state, all four texture views, exact one-shot receipts,
  and unknown/cancelled/timed-out cleanup. Live GL acceptance remains assigned to T20.
- Iris protected `.codex\`, `.vibris\`, and `common\logs\` remained untracked and unstaged. Vibris protected
  `capture\a.spv` remained untracked and unread. No deployment or Minecraft/launcher restart occurred; this
  owner-repository receipt is ledger-only.

### T17 — Integrate after-pass texture and buffer jobs

Status: `DONE`

Dependencies: T16

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ActionJobExecutor.kt`
- `I:\code\vibris\capture\src\main\kotlin\dev\luna5ama\vibris\capture\GlArtifactCapture.kt`
- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`

Scope:

- Schedule exact one-shot after-pass requests for the next frame, group safe readbacks, honor cancellation/timeouts, and publish artifact v2 receipts.
- Reuse current vertical PNG orientation and preserve raw BIN bytes.
- Return pass, frame/occurrence, logical and physical resource/view, GL metadata, artifact path, manifest, and hashes.

Non-scope:

- Do not pause or mutate shader debug output, silently fall back to another pass, or return pre-pass data.

Acceptance:

- Texture tests prove compute/graphics completion and flip-correct current/alternate views.
- Buffer sentinel tests prove complete bytes differ at the intended boundary.
- Timeout/cancel/error paths release pending registrations and restore GL state.

Verification:

- `.\gradlew.bat :vibris-capture:test :vibris-core:test --offline`
- `.\gradlew.bat :vibris-capture:test -Pvibris.runtimeTest=true`
- `cmake --build --preset release --target vibris-job-protocol-tests vibris-action-schema-tests`

Expected commit title: `T17 integrate after-pass resource dump jobs`

Blockers:

- None.

Evidence:

- Core now treats consecutive `dump_texture_after_pass` / `dump_buffer_after_pass` actions as one capture step:
  every exact request is validated and registered before any future is awaited, so the Iris T16 host can service safe
  grouped readbacks at the next exact named boundary. The action executor reserves all outputs in one artifact-v2
  transaction, rolls the group back on failure, and cancels and drains sibling registrations on timeout, cancellation,
  synchronous error, or asynchronous error.
- Successful receipts retain the exact pass ID, frame and occurrence, logical resource and selected logical view,
  physical `.main` / `.alt` or buffer name, complete GL metadata, per-file worktree-local artifact paths and SHA-256,
  plus the result manifest ID and manifest hash. Artifact metadata receives the same physical resource/view identity;
  no alternate-pass, pre-pass, alias, fallback, migration, dual-read, or compatibility path was added.
- `GlArtifactCapture` now includes shader-storage visibility for buffer readback and shader-image, texture-fetch,
  texture-update, and framebuffer visibility for texture readback. The existing PNG row inversion remains unchanged,
  BIN writes still stream the native readback bytes unchanged, and the complete pixel-pack state remains restored.
- `2026-08-11`: `.\gradlew.bat :vibris-capture:test :vibris-core:test --offline` passed with capture 26/26 and
  Core 100/100 tests, zero failures, errors, or skips. The focused Core after-pass suite passed 10/10 and proves
  all requests are registered before completion, current/alternate/buffer receipts and hashes are complete, and
  timeout, explicit cancellation, and grouped-error paths release registrations and leave no temporary manifest.
- `2026-08-11`: `.\gradlew.bat ':vibris-capture:test' '-Pvibris.runtimeTest=true'` passed. The OpenGL 4.6
  `GlArtifactCaptureRuntimeTest` ran rather than returning early (`1/1`, zero skips/failures/errors, `0.491s`) and
  proved graphics-framebuffer and compute-image texture completion, flip-correct current/alternate `2x2` PNGs,
  unchanged raw bytes, a compute-written SSBO sentinel distinct from its pre-boundary bytes, and pixel-pack restore.
- `2026-08-11`: the release CMake targets `vibris-job-protocol-tests` and `vibris-action-schema-tests` built
  successfully; direct execution passed `JobProtocolStrictV2Resume` and `ActionSchemaV2ToolContract`. The terminal
  mapping fixture preserves pass/frame/occurrence, logical/physical view, GL metadata, artifact path, manifest, and
  hashes in the MCP structured result.
- Iris protected `.codex\`, `.vibris\`, and `common\logs\` remained untracked and unstaged. Vibris protected
  `capture\a.spv` remained untracked, unread, and unstaged. No deployment or Minecraft/launcher restart occurred.

### T18 — Complete strict v2 cutover and documentation

Status: `DONE`

Dependencies: T17

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\docs\`
- `I:\code\vibris\integration-tests\`
- `I:\code\vibris\scripts\build-delivery.ps1`

Scope:

- Update examples, fixtures, probes, configuration templates, and delivery build inputs to v2 only.
- Remove all affected v1 code paths, obsolete tests, legacy state readers, old recipe control, old status fields, and dual output.
- Document clean cutover requirements, unsupported old data, recovery, artifacts, compile validation, and both after-pass actions.

Non-scope:

- Do not deploy generated artifacts or delete old user data.

Acceptance:

- Static searches and review find no affected v1/deprecated/legacy/fallback/migration branches.
- All maintained fixtures and docs describe only v2.
- A v1 Iris/MCP/Core combination fails at version negotiation rather than partially operating.

Verification:

- `rg -n "schema_version.?1|profile-matrix|restore_state_on_|args.?unknown|deprecated.*ready|legacy.*vibris" api core mcp proto integration-tests docs`
- `.\gradlew.bat build --offline`
- `cmake --build --preset release`
- `ctest --preset release`

Expected commit title: `T18 complete strict v2 cutover`

Blockers:

- None. On `2026-08-11` the user explicitly replied `批准 T18 hard cutover`, authorizing rejection of persisted
  schema-v1 configuration, workspace identity, and delivery receipts plus removal of obsolete v1-only integration
  suites. Old user data remains untouched on disk and deployment remains out of scope.

Evidence:

- `2026-08-11`: the continuation re-read this ledger and the roadmap skill completely, then verified Vibris
  `main` at `70e6967906227bda702373ee9153488dadf103e7`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree and branch, empty staging areas,
  and the exact protected untracked state in both repositories. The ledger checker reported
  `Ledger valid: 24 task(s); next=T18; READY=1, PENDING=3, BLOCKED=0, DONE=20, SUPERSEDED=0.` before this transition.
- T18 entry auditing found that the strict cutover must reject existing schema-v1 configuration, workspace identity,
  build receipts, and delivery transaction manifests without reading or migrating them. It must also remove obsolete
  integration suites tied to deleted v1 result fields while retaining their maintained strict-v2 Core/test-runtime
  coverage. The execution environment rejected both irreversible operations pending fresh user approval after these
  effects were disclosed; the agent did not retry through another mechanism.
- Partial task-owned namespace/test-runtime migration remains uncommitted for later T18 resumption. No production
  schema acceptance, legacy reader, dual output, documentation, user data, deployment, Minecraft process, launcher,
  or T19 file was changed. Protected `capture\a.spv` remained unread, untracked, and unstaged; Iris `.codex\`,
  `.vibris\`, and `common\logs\` remained untracked and unstaged.
- `2026-08-11`: the user explicitly approved the disclosed hard cutover. Entry-gate verification found Vibris
  `main` at `1ba12a1a299d4c9309bce3a3da22f4c6f349fb9f`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, all auxiliary worktrees clean at their recorded HEADs, empty staging
  areas, and only the recorded protected untracked state. T18 is restored as the sole `READY` task; implementation
  resumes in the next continuation so this permission change remains an atomic ledger-only control-plane commit.
- `2026-08-11`: resumed at Vibris `main` HEAD `8835de0bbf714972064eb4f56a3c551e585ccdf1` with Iris still at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`. Config, workspace identity, build receipts, and delivery transaction
  manifests now write and accept only schema 2; old persisted documents fail with `UNSUPPORTED_VERSION` and remain
  unchanged. gRPC submission no longer supplies missing version/workspace identity, and supplied sources require an
  explicit kind.
- Removed the obsolete pre-v2 Java integration surface, its orphaned gate scripts and fixtures, the artifact-quota
  probe, old status/recipe-control probes, unindexed shader dual output, shader/SPIR-V and sampler replay reads,
  unnamed vertex-location inference, and the `.minecraft`-prefixed path special case. Retained protocol, stdio,
  security, delivery, and source-package probes are strict v2; native reconnect/source-ownership fixtures now use
  `ResumeJob` and `JobStateSnapshot` directly.
- Replaced `docs/capture-control.md` with a strict-v2 clean-cutover guide covering unsupported old data, recovery,
  durable jobs, compile validation, benchmark gates, managed artifacts, and immediate plus named-pass texture/buffer
  actions. Maintained delivery/config templates and probes use schema 2 and `pending_source_root`.
- The required static search has matches only in this append-only ledger's historical task/evidence text; the same
  search excluding this ledger returned `STRICT_V2_SEARCH_CLEAN_EXCLUDING_LEDGER_HISTORY`. A broader removed-surface
  search returned `REMOVED_SURFACE_SEARCH_CLEAN`, and all seven retained PowerShell probes parsed successfully.
- `2026-08-11`: `.\gradlew.bat build --offline --console=plain` passed with 63 actionable tasks. The release CMake
  build completed successfully and `ctest --preset release --output-on-failure` passed 79/79 in 135.85 seconds,
  including v1/missing-version negotiation rejection, schema-1 config/identity rejection, durable resume, reconnect,
  stdio, provenance, benchmark, and security coverage.
- Protected `capture\a.spv` remained unread, untracked, and unstaged. Iris `.codex\`, `.vibris\`, and `common\logs\`
  remained untracked and unstaged. No deployment, Minecraft/launcher restart, or old-user-data deletion occurred.
- Commit: this task's commit with subject `T18 complete strict v2 cutover`.

### T19 — Run offline integrated acceptance

Status: `DONE`

Dependencies: T18

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\integration-tests\`
- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`

Scope:

- Run full Vibris Gradle/native suites, Iris compile/package checks, strict-version negative tests, durable restart tests, cross-worktree scheduler tests, and artifact/pass fixtures.
- Record exact test totals, commands, limitations, and hashes of locally built delivery artifacts without deploying them.

Non-scope:

- Do not paper over failures, deploy, restart Minecraft, or perform live acceptance.

Acceptance:

- All non-hardware automated suites pass.
- Hardware-tagged tests pass where runnable or have an explicit evidence-backed blocker that prevents promotion to T20.
- Built MCP and mod artifacts negotiate v2 and their hashes are recorded.

Verification:

- `.\gradlew.bat build --offline`
- `cmake --build --preset release`
- `ctest --preset release`
- In `I:\code\Iris`: `.\gradlew.bat :common:compileJava :fabric:build --offline`
- Run strict v1 rejection and v2 stdio/runtime fixture probes.

Expected commit title: `T19 verify offline v2 integration`

Blockers:

- None known.

Evidence:

- `2026-08-11`: entry gates verified Vibris `main` at
  `f0456e099c2d946c90f4db57fbabb3667611baf0`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree at its recorded clean HEAD, empty
  staging areas, and only the protected untracked `capture/a.spv`, Iris `.codex/`, `.vibris/`, and `common/logs/`.
  The ledger checker reported `Ledger valid: 24 task(s); next=T19; READY=1, PENDING=2, BLOCKED=0, DONE=21,
  SUPERSEDED=0.`
- `.\gradlew.bat build --offline --console=plain` passed with 63 actionable tasks. A fresh
  `.\gradlew.bat test --offline --rerun-tasks --console=plain` passed with 56 executed tasks; the resulting module
  XML reports 163 tests, zero failures/errors/skips: API 8, common 2, capture 26, Core 100, protocol Java 5,
  integration 3, test runtime 7, OpenGL replay 5, and Vulkan replay 7. The integration cases include strict-v2
  descriptor parity plus v2 hello/ping and major-version rejection.
- `cmake --build --preset release`, using the Visual Studio executable recorded in the global constraints, passed for
  the complete native target graph.
  `ctest --preset release --output-on-failure` passed 79/79 in 124.34 seconds, including strict v1/missing-version
  rejection, durable matrix and compile-validation restart, accepted-request resume, cross-worktree routing,
  provenance mutations, managed artifact/state rejection, benchmark gates, and stdio lifecycle. A focused rerun of
  the two version-negative plus four stdio/restart fixtures passed 6/6 in 6.69 seconds; the direct built-executable
  probe reported `PASS tools=8 schema_version=2 ... request_scoped=true` and cleaned all owned processes/temp state.
- Cross-worktree scheduler, artifact-v2 lifecycle, after-pass resource execution, and transactional restoration Core
  fixtures passed 1/1, 5/5, 10/10, and 8/8 respectively. The hardware-enabled
  `.\gradlew.bat ':vibris-capture:test' '-Pvibris.runtimeTest=true' --offline --rerun-tasks --console=plain` run
  passed the 26/26 capture suite and actually executed all four OpenGL runtime fixtures (4/4, zero failures/errors/
  skips), including flip-correct framebuffer/image texture capture, compute-written SSBO bytes, unsupported-texture
  handling, GPU timing, and complete pixel-pack restoration.
- In Iris, `.\gradlew.bat :common:compileJava :fabric:build --offline --console=plain` passed with 41 actionable
  tasks and regenerated the remapped local mod. The forced
  `.\gradlew.bat :fabric:vibrisBridgeTest --offline --rerun-tasks --console=plain` run passed 15/15 tests with 36
  executed tasks, covering effective settings, compile catalogs, runtime adaptation, and named-pass texture/buffer
  boundaries.
- The C++/Java/proto descriptor probe passed with canonical descriptor SHA-256
  `EC0A1A6995BF93C13BBD4185832A75BF0B9E6F7BCEE0A97ACF5FB208B4B9C796`. The remapped Iris JAR contains 536
  `dev/vibris/protocol/v2` entries and zero v1 entries. Local, non-deployed delivery outputs are:
  `mcp/out/build/Release/vibris-mcp.exe` (15,350,272 bytes, SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`) and
  `I:\code\Iris\build\libs\iris-fabric-1.10.6-snapshot+mc1.21.11-local.jar` (28,145,118 bytes, SHA-256
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`). The Java protocol JAR is 1,581,411
  bytes with SHA-256 `2F7509DA2DB0A1A1A0C449CF96F16465254702143B31E6920BF6A939282043F9`.
- Limitation: this acceptance is deliberately offline. The artifacts were verified and hashed in their local build
  locations without packaging into or deploying a live delivery; no live Minecraft acceptance, deployment,
  Minecraft/launcher restart, or user-data mutation occurred. Those live receipts remain exclusively assigned to
  T20. Protected and user-owned untracked state remained unchanged and unstaged.
- Commit: this task's commit with subject `T19 verify offline v2 integration`.

### T20 — Run live two-worktree 720p acceptance

Status: `BLOCKED`

Dependencies: T19

Repository: `I:\code\vibris` and `I:\code\Iris`

Worktree: `I:\code\vibris` ledger owner; user-supplied shader worktrees for runtime requests

Branch: Vibris `main`; Iris `1.21.11-shaderdev`

Primary files:

- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`
- Runtime artifacts under the supplied worktrees' `.vibris\artifact` directories

Scope:

- Against a user-started matching v2 Minecraft/Iris runtime, run two worktrees through fair queueing, 720p preset load, compile validation, effective-settings preserve/override, transactional restore, paired benchmark, visual gate, texture-after-pass, and buffer-after-pass.
- Record request/job IDs, artifact paths, manifest/hash receipts, source identities, status transitions, and final restoration proof.

Non-scope:

- Do not restart Minecraft, switch launchers, deploy builds, or choose shader worktrees without explicit user-provided runtime scope.

Acceptance:

- Both worktrees show correct owner/queue behavior against one runtime.
- Compile, visual, provenance, restoration, statistics, and artifact gates pass.
- Texture view/flip and buffer sentinel results prove exact pass-boundary timing.
- Runtime returns to the original source/config/scene and can accept a new job.

Verification:

- Live v2 MCP requests and returned structured receipts.
- SHA-256 verification of result, screenshot, texture, buffer, and manifest artifacts.
- Final `vibris_get_status` summary and protected Git-state checks.

Expected commit title: `T20 record live v2 acceptance`

Blockers:

- Requires the user to separately deploy and start the matching strict-v2 MCP/Iris build and to provide two explicit
  shader worktrees. The currently running Minecraft instance and configured MCP are pre-v2 and cannot be used through
  a compatibility path. Never satisfy this blocker by autonomously deploying, restarting, or choosing worktrees.

Evidence:

- `2026-08-11`: entry gates verified Vibris `main` at
  `4fe98606bb879f589ea706e6f11f76981d8fec76`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree at its recorded clean HEAD, empty
  staging areas, and only the protected untracked state. The ledger checker reported
  `Ledger valid: 24 task(s); next=T20; READY=1, PENDING=1, BLOCKED=0, DONE=22, SUPERSEDED=0.`
- A pre-existing Java runtime is listening on `127.0.0.1:50051` as PID 63232 from the
  `I:\MultiMC\instances\1.21.11-Iris` instance. Read-only inspection proved that its installed
  `iris-fabric-1.10.6-snapshot+mc1.21.11-local.jar` is 27,279,850 bytes with SHA-256
  `551D72EACE461C72DECCDFF2AB7E946D646765C6CA5457A0E3EFED7165606B7C`; its nested protocol JAR contains 335
  `dev/vibris/protocol/v1` entries and zero v2 entries. It therefore cannot negotiate the strict-v2 T20 contract.
- `C:\Users\Luna5ama\.codex\config.toml` points Vibris at
  `I:\code\vibris\build\delivery-20260810-png-flip\vibris-mcp.exe` (15,038,464 bytes, SHA-256
  `AB86DD12A4F2E5B1E07DC3EA77E0C9F36A1232654799D1A4952789B8528D66E9`), not T19's verified v2 executable
  (SHA-256 `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`). A read-only stdio `tools/list`
  probe returned only the old five-tool surface (`vibris_list_presets`, `vibris_get_status`, `vibris_run_recipe`,
  `vibris_run_actions`, and `vibris_run_matrix`) with no schema-v2 metadata, rather than the required eight tools.
- No two shader worktrees were explicitly supplied for this live acceptance. No worktree was inferred, and no live
  status/action request was issued against an arbitrarily chosen repository. No deployment, Minecraft/launcher
  restart, runtime mutation, artifact write, or protected-state change occurred.
- T20 remains incomplete and T99 remains `PENDING`. Resume T20 only after the user starts a matching strict-v2
  Minecraft/Iris runtime, configures the matching v2 MCP delivery, and supplies the two exact shader worktree paths.
- `2026-08-11` blocker audit 2: Vibris remained at
  `8d73da8e211ebc2278088733f2f11ec24446f18e`, Iris remained at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, all auxiliary worktrees and staging areas remained clean, and the
  ledger checker still reported `READY=0, PENDING=1, BLOCKED=1, DONE=22`. The same PID 63232 continued listening on
  port 50051 from its original start time; the configured MCP hash remained
  `AB86DD12A4F2E5B1E07DC3EA77E0C9F36A1232654799D1A4952789B8528D66E9` and again published only the five old
  tools. The installed mod hash remained `551D72EACE461C72DECCDFF2AB7E946D646765C6CA5457A0E3EFED7165606B7C`
  with 335 v1 and zero v2 protocol entries. No two shader worktrees were supplied, and no deployment, restart,
  runtime request, artifact write, or protected-state change occurred. This is the second consecutive Goal turn with
  the same external blocker; T20 remains `BLOCKED` and the Goal remains active.
- `2026-08-11` blocker audit 3: Vibris entered at
  `3654e963195ce02b69a07cd625114f5c32b327ea`, Iris remained at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every auxiliary worktree remained at its recorded clean HEAD, all
  staging areas were empty, and the ledger checker reported
  `Ledger valid: 24 task(s); next=none; READY=0, PENDING=1, BLOCKED=1, DONE=22, SUPERSEDED=0.` The same Java PID
  63232 still listened on `127.0.0.1:50051` from its original `2026-08-11T19:26:38.4366820Z` process start. The
  configured MCP remained the 15,038,464-byte executable with SHA-256
  `AB86DD12A4F2E5B1E07DC3EA77E0C9F36A1232654799D1A4952789B8528D66E9`; an isolated read-only stdio probe again
  negotiated MCP `2024-11-05` and listed only the same five old tools. The installed mod remained 27,279,850 bytes
  with SHA-256 `551D72EACE461C72DECCDFF2AB7E946D646765C6CA5457A0E3EFED7165606B7C`, 335 nested protocol-v1 entries, and
  zero v2 entries. The user supplied no two shader worktrees. No live status/action request, deployment, restart,
  runtime mutation, artifact write, or protected-state change occurred. This is the third consecutive Goal turn with
  the same external blocker; after this ledger-only atomic checkpoint the Goal is marked `blocked`, while T20 stays
  `BLOCKED` and T99 stays `PENDING`.

### T99 — Final integrated audit

Status: `PENDING`

Dependencies: T01, T02, T02A, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T12A, T13, T14, T15, T16, T17, T18, T19, T20

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`
- Final maintained v2 source, schema, tests, docs, and build inputs in both repositories

Scope:

- Reconcile every task with Git history and durable evidence, resolve all external Iris SHAs, rerun final focused/integrated checks, and audit generated outputs, documentation, artifacts, and protected state.
- Confirm the strict no-compatibility requirement and complete the task-to-commit map.

Non-scope:

- Do not add unplanned product features, rewrite completed commits, deploy, restart Minecraft, or clean user data.

Acceptance:

- No required task remains `READY`, `PENDING`, or `BLOCKED` after this task completes.
- Every global acceptance item is checked with durable evidence.
- Every expected task ID maps to the intended full commit in the correct repository.
- Protected and pre-existing user state remains intact.
- Final Git, build, test, artifact, schema, and live receipts are recorded.

Verification:

- `python C:\Users\Luna5ama\.codex\skills\run-persistent-roadmap\scripts\check_ledger.py I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`
- `.\gradlew.bat build --offline`
- `cmake --build --preset release`
- `ctest --preset release`
- In `I:\code\Iris`: `.\gradlew.bat :common:compileJava :fabric:build --offline`
- `git diff --cached --check`, staged names/stat/full diff, final HEAD/branch/worktree/protected-state checks in both repositories.

Expected commit title: `T99 finalize engineering validation v2`

Blockers:

- None known beyond unresolved earlier tasks.

Evidence:

- Pending.

## Dependency order

```text
T00 -> T01 -> T02 -> T02A -> T03 -> T04 -> T05 -> T06 -> T07 -> T08 -> T09
    -> T10 -> T11 -> T12 -> T12A -> T13 -> T14 -> T15 -> T16 -> T17 -> T18
    -> T19 -> T20 -> T99
```

Queue order is authoritative and serial even where technical dependencies could allow parallel work.

## Global acceptance checklist

- [x] MCP publishes exactly the eight typed v2 tools and never duplicates the structured payload.
- [x] No affected v1 compatibility parser, alias, adapter, fallback, migration, dual-read, or dual-write remains.
- [x] Shared runtime ownership, queue, progress, error history, readiness, waits, cancellation, and recovery are truthful.
- [x] Long jobs are durable, queryable, resumable when safe, and never duplicate completed or uncertain side effects.
- [x] All state-mutating validation restores source, effective settings, scene, and temporal state with receipts.
- [x] Preserve returns complete effective settings, origins, diff, and stable hash.
- [x] Every input action produces exactly one ordered receipt.
- [x] Compile validation reports every intended program/pass and baseline diagnostic changes without GPU warmup.
- [x] Every result contains complete immutable provenance and correct shader-content stale semantics.
- [x] Benchmark decisions enforce target/sibling/sentinel guardrails, measured noise, confidence, order, drift, compile, visual, provenance, and restoration gates.
- [x] Artifact v2 supports TTL, hash manifests, request/job grouping, capacity prediction, ownership-safe list/detail/delete, and worktree-local paths.
- [x] `dump_texture_after_pass` and `dump_buffer_after_pass` capture exact named pass boundaries with correct flip, visibility, bytes, artifacts, and GL-state restoration.
- [x] Full Vibris native/Gradle and Iris build validation passes.
- [ ] Live two-worktree 720p acceptance passes without autonomous deployment or process restart.
- [ ] Every expected commit is present in the intended repository and branch.
- [x] Protected and pre-existing user state remains untouched.
- [ ] Final worktree, branch, ledger, and Goal audits pass.

## Completion evidence

Record final artifact paths, hashes, test totals, live request/job receipts, repository heads, and task-to-commit mappings here during T99.

## Completion log

- `2026-08-11 - T00 - initialized and validated 22-task strict-v2 serial ledger; verified both repository identities and protected concurrent dirty state - Commit title: T00 persist engineering validation v2 ledger`
- `2026-08-11 - T01 - generated strict v2 Java/C++ protocol; Java 5/5 and native CTest 5/5 passed; v1 and missing versions reject as UNSUPPORTED_VERSION - Commit title: T01 replace control protocol with strict v2`
- `2026-08-11 - T02 - published exactly eight typed MCP v2 tools; native CTest 5/5 and direct schema/stdio fixtures passed; removed old control client and v1 stdio fixture dependency - Commit title: T02 publish compact typed MCP v2 tools`
- `2026-08-11 - T02A inserted - T03 baseline exposed 44 Core files still importing the deleted v1 Java package and broader removed-v1 API usage; inserted a strict-v2 Core migration remediation before T03 - Control-plane commit title: roadmap insert T02A strict v2 Core remediation`
- `2026-08-11 - T02A - migrated maintained Core directly to strict v2 jobs, status, actions, artifacts, and tests; removed v1-only benchmark isolation; protocol/Core tests passed with 83 Core tests and zero v1 references - Commit title: T02A migrate Core directly to strict v2`
- `2026-08-11 - T03 - exposed authoritative runtime lease, fair cross-workspace queue, truthful readiness/error/transitions, safe cancellation, and event-driven status waits; Core 86/86 and native filtered CTest 7/7 passed - Commit title: T03 expose runtime lease and status transitions`
- `2026-08-11 - T06 - required complete runtime-resolved shader settings, deterministic origins/default diff/hash, and propagated them through mutation/restoration provenance; API 6/6 and Core 95/95 passed - Commit title: T06 expose resolved shader settings contract`
- `2026-08-11 - T07 - Iris snapshots active settings before preserve reloads and returns complete post-install values/defaults/origins/hash; bridge tests 10/10 and Iris offline build passed; external commit 7096295b3875a13b6f00607b6f30d0649bd4f68f - Commit title: T07 report effective shader settings from Iris`
- `2026-08-11 - T08 - returned one terminal receipt per input action, separated generated preludes, retained grouped-capture indices, nested screenshot waits, and preserved partial receipts on failure; protocol Java 5/5, Core 97/97, focused 13/13, and native CTest 2/2 passed - Commit title: T08 return complete ordered action receipts`
- `2026-08-11 - T09 blocked - operational compile-catalog query requires the still-dirty protected ThreadBound runtime adapter; no fallback or compatibility path was added and T10 was not started - Control-plane commit title: roadmap block T09 on protected runtime adapter`
- `2026-08-11 - T09 unblocked - user committed the protected Vibris adapter/test as b7a0931d85042442c0360a38c50e30d811be9486 and Iris lifecycle as 6322cb2833edfddbfa64d0ac6001988c4d49efd1; tracked dirt is resolved and T09 is READY - Control-plane commit title: roadmap unblock T09 after user merges`
- `2026-08-11 - T09 - defined canonical compile catalogs and stable diagnostic fingerprints, replaced string shader inspection with a typed runtime query/receipt, and passed API 7/7 plus Core 101/101 tests - Commit title: T09 define compile validation catalog contract`
- `2026-08-11 - T10 - Iris emits complete canonical program/pass compile catalogs with terminal diagnostics and patched-source identities only after pipeline/reload completion; external commit 0ccdadd3a9b80891d147ace95a3c3919b7055b76; bridge tests 11/11 and offline Iris build passed - Owner receipt commit title: T10 record Iris compile catalog receipt`
- `2026-08-11 - T11 - added durable sync/async compile_validate for single and matrix cases with optional baseline, canonical catalog and stable diagnostic diffs, per-case checkpoints/provenance, fail-closed compile gates, and forced runtime restoration; Core focused 10/10 and native filtered CTest 3/3 passed - Commit title: T11 add compile validation recipe`
- `2026-08-11 - T12 - expanded immutable source/result provenance, separated HEAD movement from shader-content staleness with deterministic completion deltas, retained provenance through durable checkpoints, and proved active source UUIDs on compile/action receipts; Core 103/103, focused native 7/7, and source/checkpoint regression 14/14 passed - Commit title: T12 expand benchmark provenance and stale checks`
- `2026-08-11 - T12A inserted - T13 entry auditing found native profile/matrix normalization and fake-server fixtures still consuming removed pre-v2 result fields instead of strict-v2 action receipts, top-level provenance, and restoration; inserted a no-compatibility receipt-normalization remediation before statistical guardrails - Control-plane commit title: roadmap insert T12A strict v2 result remediation`
- `2026-08-11 - T12A - normalized profile, matrix, inspection, GPU metrics, and A/B visual results from strict-v2 typed receipts only; replaced the v1 fake-server fixture; focused CTest 4/4 and broader visual/paired/checkpoint regression 9/9 passed with zero removed-field or compatibility reads - Commit title: T12A normalize strict v2 execution receipts`
- `2026-08-11 - T13 - enforced typed target/sibling/sentinel guardrails, paired p50/p95 confidence and measured-noise decisions, order/reversal/drift reporting, and mandatory compile/provenance/restoration/visual/statistical gates; release targets built and focused CTest passed 12/12 - Commit title: T13 enforce benchmark semantic guardrails`
- `2026-08-11 - T14 - replaced artifacts with strict-v2 TTL/hash/grouped manifests, capacity reservations, ownership-safe list/detail/delete, worktree-local MCP paths, and durable expired metadata; Core 94/94 plus focused native 3/3 passed - Commit title: T14 add managed artifact v2 lifecycle`
- `2026-08-11 - T16 - Iris registered ordered named pass boundaries, captured flip-correct texture/SSBO snapshots only after compute/graphics completion, moved encoding/writes off-thread, and removed cancelled/timed-out hooks; external commit 38a7d2eaf88939983e0e01f731ccd4c627fbf6a9; bridge tests 15/15 and offline Iris build passed - Owner receipt commit title: T16 record Iris named pass capture receipt`
- `2026-08-11 - T17 - grouped exact next-frame after-pass texture/buffer registrations into one artifact-v2 transaction, returned complete physical-view/pass/hash receipts, strengthened GPU visibility barriers, preserved flipped PNG/native BIN semantics, and released timeout/cancel/error registrations; capture 26/26, Core 100/100, real OpenGL 4.6 runtime 1/1, and native protocol/schema 2/2 passed - Commit title: T17 integrate after-pass resource dump jobs`
- `2026-08-11 - T18 blocked - hard cutover rejects persisted schema-v1 state and removes obsolete v1-only integration suites; execution requires fresh explicit user approval after impact disclosure, while old data remains untouched and no deployment occurs - Control-plane commit title: roadmap block T18 pending hard cutover approval`
- `2026-08-11 - T18 unblocked - user explicitly approved the disclosed hard cutover; schema-v1 state may be rejected and obsolete v1-only suites removed while old data remains untouched and deployment stays out of scope - Control-plane commit title: roadmap unblock T18 after hard cutover approval`
- `2026-08-11 - T18 - completed the schema-2 hard cutover, removed obsolete pre-v2 integration/probe/fixture and dual-read/write paths, rewrote the operator guide, passed Gradle plus the release CMake build and 79/79 CTest cases, and left protected/runtime/user data untouched - Commit title: T18 complete strict v2 cutover`
- `2026-08-11 - T19 - passed fresh 163-test JVM, 79-test native, 15-test Iris bridge, and four-test OpenGL runtime acceptance; proved strict-v2 negotiation/restart and recorded hashes for the local MCP/mod delivery outputs without deployment - Commit title: T19 verify offline v2 integration`
- `2026-08-11 - T20 blocked - the live instance embeds 335 v1 and zero v2 protocol entries, the configured MCP publishes only the old five-tool surface, and no two shader worktrees were supplied; no compatibility path, deployment, or restart was attempted - Control-plane commit title: roadmap block T20 pending matching live scope`
- `2026-08-11 - T20 blocker audit 2 - reverified the unchanged pre-v2 runtime/MCP and missing two-worktree scope; this is the second consecutive blocked Goal turn, so the Goal remains active - Control-plane commit title: roadmap recheck T20 live scope blocker`
- `2026-08-11 - T20 blocker audit 3 - reverified the unchanged pre-v2 runtime/MCP and missing two-worktree scope for the third consecutive blocked Goal turn; the Goal is marked blocked after the ledger-only atomic checkpoint - Control-plane commit title: roadmap confirm T20 blocked awaiting live scope`
