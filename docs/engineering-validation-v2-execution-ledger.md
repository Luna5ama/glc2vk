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
  On `2026-08-12`, the user refined the session authorization: the existing MultiMC `1.21.11-Iris` instance may be
  restarted and a task-owned Mod may be redeployed when required, but new MCP validation must invoke the built
  executable directly without changing the Codex MCP deployment/configuration or restarting Codex.
- On `2026-08-12`, the user replaced AP4 as the deterministic visual-validation target with a 1.10 or 1.9 branch and
  authorized ignoring the AP4 voxel-specific divergence when it is not material to the Vibris contract. Use the
  existing clean `I:\code\mcshaders\Alpha-Piscium-8` worktree at branch `1.10/fsr3`, exact HEAD
  `0c4112620b15dfd3b7684221714f58bda4fb6439`, for the remaining same-source temporal/visual proof. Do not weaken a
  threshold, modify a shader, or reinterpret a failed comparison; AP4's allocator output is simply outside that proof.
- On `2026-08-13`, after the unchanged EnvProbe scatter race was reproduced on both authorized 1.10 and 1.9 lines, the
  user authorized a fresh synthetic validation target under `I:\code\mcshader`. The target must be a clean local Git
  worktree with one recorded commit containing only a minimal compute-shader test package whose compute shader writes a
  deterministic color pattern directly. Use that exact clean commit for both sides of the temporal/visual proof; do
  not modify the supplied Alpha-Piscium worktrees, weaken thresholds, or add a compatibility/state-reuse bypass.
- Pass-boundary capture v2 covers only named begin, prepare, deferred, composite, final, and shadow-composite stages; high-frequency gbuffer, terrain, and ordinary shadow draws are out of scope.
- Artifact TTL defaults to 168 hours.
- `cmake` and `ctest` are not on `PATH`; ledger commands use the Visual Studio copies under `C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin`.
- Preserve all unrelated and pre-existing user changes; never stage a whole repository.
- Keep Vibris and Iris commits atomic and separate.

## Protected and user-owned state

| Repository | Path or state | Owner | Required handling |
|---|---|---|---|
| `I:\code\vibris` | `capture\a.spv` untracked | User | Never read as a fixture, modify, delete, stage, or commit. |
| `I:\code\vibris` | T19H changes in `mcp\src\main\cpp\job_protocol.cpp`, `paired_benchmark.cpp`, and the four matching focused test files | Codex/T19H retained implementation | Preserve unstaged while T19I is implemented. Stage only when T19H itself reaches every acceptance gate. |
| `I:\code\vibris` | `core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt` and its test | User change committed as `b7a0931d85042442c0360a38c50e30d811be9486` | Resolved; the files are clean and may be modified by later task-owned work. |
| `I:\code\vibris` | Detached review worktrees under `I:\code\vibris-review-*` | User/review tooling | Do not modify, remove, or use as implementation targets. |
| `I:\code\Iris` | `.codex\` untracked | User/Codex runtime | Preserve and never stage. |
| `I:\code\Iris` | `.vibris\` untracked | Runtime artifacts | Preserve and never stage. |
| `I:\code\Iris` | `common\logs\` untracked | Runtime logs | Preserve and never stage. |
| `I:\code\Iris` | `common\src\main\java\net\irisshaders\iris\vibris\IrisVibrisLifecycle.java` | User change committed as `6322cb2833edfddbfa64d0ac6001988c4d49efd1` | Resolved; the file is clean and may be modified by later task-owned work. |
| `I:\code\mcshaders\Alpha-Piscium-8` | Clean `1.10/fsr3` at `0c4112620b15dfd3b7684221714f58bda4fb6439` | User-supplied live validation target | Do not modify or stage shader source; ignored `.vibris` runtime artifacts may be produced only by authorized live verification. |
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
| T19A | P0 | Vibris | Repair strict v2 live bootstrap | DONE | `T19A repair strict v2 live bootstrap` |
| T19B | P0 | Vibris | Compact capture results and localize artifact paths | DONE | `T19B compact capture results and localize artifact paths` |
| T19C | P0 | Iris | Make the main-menu runtime ready for jobs | DONE | `T19C make main menu runtime ready` |
| T19D | P0 | Vibris | Return typed live GPU metric receipts | DONE | `T19D return typed GPU metric receipts` |
| T19E | P0 | Vibris | Define strict detached-worktree provenance contract | DONE | `T19E define strict detached provenance contract` |
| T19F | P0 | Iris | Implement and prove live detached-worktree provenance | DONE | `T19F complete live detached provenance` |
| T19G | P0 | Vibris | Repair live paired-benchmark finalization | DONE | `T19G repair live paired benchmark finalization` |
| T19I | P0 | Vibris/Iris | Add a frame-atomic deterministic temporal capture phase | DONE | `T19I record deterministic temporal phase proof` |
| T19H | P0 | Vibris | Make paired visual capture deterministic | DONE | `T19H make paired visual capture deterministic` |
| T20 | P0 | Vibris/Iris | Run live two-worktree 720p acceptance | READY | `T20 record live v2 acceptance` |
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

### T19A — Repair strict v2 live bootstrap

Status: `DONE`

Dependencies: T19

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ServerConfiguration.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\UnavailableVibrisControlService.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ActionJobExecutor.kt`
- `I:\code\vibris\mcp\src\main\cpp\tool_registry.cpp`
- `I:\code\vibris\mcp\src\main\cpp\tool_argument_policy.cpp`
- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`
- Focused strict-v2 bootstrap, unavailable-service, action-schema, and job-protocol tests

Scope:

- Generate a fresh schema-2 `server.json` with the fixed Iris pack root
  `<game>\shaderpacks\vibris`; retain strict ordinary-directory validation and never infer a parent/junction workaround.
- Implement the maintained unary v2 service methods on the unavailable Core service so all eight MCP tools receive an
  explicit structured server/config/runtime failure rather than gRPC `UNIMPLEMENTED`.
- Align the public `load_shader` action end to end on the typed `source_id` and `config_id` fields only.
- Make a clean runtime able to execute the first explicit non-restoring load and then `load_and_screenshot` without a
  sacrificial profile. Capture resource planning and authoritative reservation must use the post-load catalog when the
  same action sequence contains the generated load prelude.

Non-scope:

- Do not migrate, read, overwrite, delete, or accept schema-v1 configuration or state.
- Do not add aliases for `source` / `config`, a parent shaderpack-root fallback, a synthetic resource catalog, or any
  other compatibility path.
- Do not deploy, restart Minecraft, resume T20, or modify shader worktrees in this task.

Acceptance:

- An isolated missing-config fixture writes schema 2 with `shaderpack_root=<game>\shaderpacks\vibris`; schema-v1 input
  remains untouched and rejected, and strict ordinary-root checks remain fail closed.
- Every maintained unavailable unary RPC returns its typed schema-2 envelope and explicit failure; none falls through
  to gRPC `UNIMPLEMENTED`.
- Public `vibris_run_actions` accepts a `load_shader` using declared `source_id` / `config_id`; the obsolete
  `source` / `config` shape is rejected rather than translated.
- A fresh-runtime fixture with an empty pre-load resource catalog establishes the first safe snapshot using one
  explicit false/false load, then completes `load_and_screenshot` without a profile or other bootstrap job; screenshot
  planning and capacity reservation are proven to use the catalog published after the load prelude.

Verification:

- `.\gradlew.bat :vibris-core:test --offline --console=plain`
- `cmake --build --preset release --target vibris-action-schema-tests vibris-job-protocol-tests`
- `ctest --preset release -R "ActionSchema|JobProtocol|Unavailable|Bootstrap" --output-on-failure`
- Run the focused clean-config and fresh-runtime bootstrap fixtures, then statically prove there are no legacy
  `source` / `config` load aliases, schema-v1 migration paths, parent-root fallbacks, or pre-load catalog substitutes.

Expected commit title: `T19A repair strict v2 live bootstrap`

Blockers:

- None known.

Evidence:

- `2026-08-11`: the user-authorized cutover archived the untouched 366-byte schema-v1 configuration as
  `server.schema-v1-20260812T021504Z.json` with SHA-256
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`. Minecraft generated a schema-2 default,
  but its `shaderpack_root=<game>\shaderpacks` both failed this host's strict junction check and omitted the fixed
  `vibris` pack component required by Iris. Setting the strict ordinary root to `I:\code\mcshaders\vibris` produced
  the working 486-byte configuration with SHA-256
  `6607CF94249CE8335CEA7FEAB1DE98A1A8ACA5F96256CECF4003DC3B86F94EFA`.
- The matching runtime now lists `night-gi-1-720p` version 2 at 1280x720 with preset SHA-256
  `d3d37c2f3d751464214223d06ddd8b8924a54ac28be978608fd7eff5ea16dece`. Transactional job/request
  `c784be59-5c2f-4854-a809-9855325af585` completed after a sacrificial profile established the first safe snapshot;
  its screenshot is 2,209,250 bytes with SHA-256
  `16A20E15F37411BBDF78E2FBE5E238FC3DF051674881E03B1A0D9E90EA3E50E5`, and final status is available with
  `can_accept_job=true`, `can_start_job=true`, and active source UUID `0248adc2-a449-43c2-bb9d-86cca202e4b1`.
- Live failure `93bb5829-5193-4cdb-8eca-b0d9b0c57d83` proved fresh `load_and_screenshot` resolves
  `final_framebuffer` before its load prelude. Source inspection confirms `ActionJobExecutor` calls
  `prepareActions(... runtime.getResourceCatalog() ...)` before executing any load step. The public schema and
  `job_protocol.cpp` require `source_id` / `config_id`, while `tool_argument_policy.cpp` validates nonexistent
  `source` / `config` fields, making the documented first non-restoring load impossible through MCP.
- `UnavailableVibrisControlService` overrides only server info, status, and the control stream, so `ListPresets` and
  the other maintained unary calls fall through to gRPC `UNIMPLEMENTED` when configuration/runtime bootstrap fails.
  No product source, deployment, process, or shader worktree was changed during this control-plane insertion.
- `2026-08-11`: entry gates verified Vibris `main` at
  `3335eb203f38fd43aec14ceebe14de2673599af2`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared review/runtime worktree and branch, empty staging areas,
  and all protected/user-owned dirty state. T19 commit `4fe98606bb879f589ea706e6f11f76981d8fec76` is an ancestor;
  the ledger checker reported `Ledger valid: 26 task(s); next=T19A; READY=1, PENDING=3, BLOCKED=0, DONE=22,
  SUPERSEDED=0.`
- Fresh configuration now writes schema 2 with the strict ordinary root `<game>\shaderpacks\vibris`. The focused
  fixtures prove that directory is created, a configured symbolic-link root is rejected without a parent fallback,
  and schema-v1 content remains byte-for-byte untouched while failing with `UNSUPPORTED_VERSION`.
- `UnavailableVibrisControlService` now implements all six unary v2 RPCs plus the control stream. Loopback gRPC
  coverage proves `GetServerInfo` and `GetStatus` return protocol-major-2 failed status envelopes, while presets,
  resources, context validation, and artifact management return explicit `UNAVAILABLE` failures containing
  `ERROR_CODE_SERVER_NOT_AVAILABLE`; no method returns gRPC `UNIMPLEMENTED`.
- The public `load_shader` action policy now resolves only declared `source_id` / `config_id`. The action-schema fixture
  accepts that exact typed shape and rejects obsolete `source` / `config`; the JobProtocol fixture preserves the
  selected source UUID, config values, and identifiers and proves `load_and_screenshot` emits exactly one load prelude
  followed by its wait and screenshot.
- Action execution completes a first load prelude before resolving capture resources or opening its authoritative
  artifact reservation, then plans against the post-load resource catalog and skips the already completed load step.
  `freshRuntimeLoadsThenPlansScreenshotFromPostLoadCatalog` starts from an empty catalog, establishes the safe snapshot
  with one explicit false/false load, publishes the catalog during the generated prelude, completes the screenshot and
  transactional restore, and proves the job performed exactly one load rather than a sacrificial or duplicated load.
- `\.\gradlew.bat :vibris-core:test --offline --console=plain` passed 102/102 tests with zero failures, errors, or
  skips; `VibrisBootstrapTest` passed 16/16 and `RuntimeJobExecutorCaptureTest` passed 11/11. Release native targets
  `vibris-action-schema-tests` and `vibris-job-protocol-tests` built successfully, and
  `ctest --preset release -R 'ActionSchema|JobProtocol|Unavailable|Bootstrap' --output-on-failure` passed 2/2.
  Static checks found only `source_id` / `config_id` in the load action schema/policy/protocol and no compatibility,
  legacy, fallback, migration, schema-v1, or parent-root branch in the affected production files.
- No deployment, configuration mutation outside isolated test directories, Minecraft/launcher restart, live T20
  request, or shader-worktree edit occurred. Protected Vibris `capture/a.spv`, Iris runtime directories, and all
  unrelated Alpha-Piscium worktree changes remained unstaged and untouched. Commit: this task's commit with subject
  `T19A repair strict v2 live bootstrap`.

### T19B — Compact capture results and localize artifact paths

Status: `DONE`

Dependencies: T19A

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\synchronous_job_runner.cpp`
- `I:\code\vibris\mcp\src\main\cpp\workspace_artifact_link.cpp`
- `I:\code\vibris\mcp\src\test\cpp\synchronous_job_runner_tests.cpp`
- A focused `workspace_artifact_link` test target and CTest registration

Scope:

- Project synchronous `load_and_screenshot` results into a compact task-specific schema containing the request/job
  identity and state, the primary screenshot identity/path/hash/size/resource metadata, manifest identity/path/hash,
  concise restoration state, source/config/preset identities, and useful timings.
- Keep the complete immutable Core `JobResult`, effective settings, provenance, action/prelude receipts, and all audit
  detail in its durable result artifact and explicit job/artifact inspection surfaces; do not repeat those structures
  in the synchronous screenshot response or duplicate artifact arrays at multiple levels.
- Recursively discover and rewrite every strict-v2 user-visible `relative_path`, `manifest_path`, and `log_path` in a
  tool outcome, including nested results, receipts, manifests, diagnostics, and matrix/case envelopes. Validate every
  target before mutation, create or verify the request worktree `.vibris\artifact` link, and return only localized
  absolute paths beneath that link.
- Preserve fail-closed `ARTIFACT_LINK_ERROR` behavior for missing, cross-workspace, reparse-point, or non-ordinary
  targets, and prevent partial rewrites or Minecraft-internal artifact-root leakage.

Non-scope:

- Do not add v1 parsing, aliases, compatibility projections, fallback roots, or dual response shapes.
- Do not change Core artifact storage, truncate durable result files, or discard immutable audit evidence.
- Do not deploy, restart Minecraft, resume T20, or modify either shader worktree in this task.

Acceptance:

- A fixture matching the demonstrated raw screenshot result produces a bounded compact structured payload no larger
  than 16 KiB, contains zero `changed_from_default` entries and no raw action/prelude receipts, and exposes exactly
  one primary screenshot handle plus one manifest handle while retaining IDs, hashes, dimensions, restoration, and
  required source/config/preset identity.
- The complete durable result artifact remains available, byte/hash stable, and contains the full provenance and
  effective-setting evidence omitted from the synchronous response.
- Every returned artifact, manifest, and log path anywhere in the final tool outcome is absolute beneath
  `<worktree>\.vibris\artifact`; no `I:\MultiMC\...\.minecraft\vibris\artifacts` or other backing-store path survives.
- Recursive rewriting covers nested action/prelude receipts, result envelopes, diagnostics, manifests, and case
  collections, while invalid targets fail before any path is rewritten.

Verification:

- `cmake --build --preset release --target vibris-synchronous-job-runner-tests vibris-workspace-artifact-link-tests`
- `ctest --preset release -R "StrictV2Result|ScreenshotResult|ArtifactLink|JobProtocol" --output-on-failure`
- Run focused compact-result and recursive-path fixtures; statically prove the synchronous screenshot projection has
  no full effective-settings/action-receipt copy and that every user-visible path field traverses the strict rewrite.

Expected commit title: `T19B compact capture results and localize artifact paths`

Blockers:

- None.

Evidence:

- `2026-08-11`: returned `load_and_screenshot` request/job
  `fb578c26-e8a0-47de-9fca-68d470b7e730` was 192,710 characters across 5,083 lines and repeated 692
  `changed_from_default` setting entries through the raw result/provenance/receipt envelope. It returned five
  Minecraft-internal artifact paths and zero paths beneath the request worktree `.vibris\artifact` link.
- The screenshot artifact is 1,888,374 bytes with SHA-256
  `34D494A164A4E08B0FE2BA2A5C0D50A0F87B03AB4523F1ACD256981CC1ABFFF5`. Source inspection shows
  `SynchronousJobRunner::run` special-normalizes analysis recipes but passes `load_and_screenshot` through as the raw
  terminal job envelope. `WorkspaceArtifactLink::collect_paths` only inspects shallow legacy-shaped collections and
  misses strict-v2 nested `relative_path` fields, despite `McpBackend` invoking the rewriter.
- No product source, deployment, process, configuration, artifact, or shader worktree was changed during this
  control-plane insertion.
- Added a strict `load_and_screenshot` projection that retains terminal job/request state, one primary screenshot
  handle with complete resource dimensions and hashes, one manifest handle, source/config/preset identities, concise
  restoration verification, and timings. It omits the raw provenance, effective-setting arrays, action/prelude
  receipts, generic artifact arrays, result artifact, and shader-log duplication from the synchronous response while
  leaving the complete raw terminal result and its artifact/hash identities unchanged for job/artifact inspection.
- Replaced the shallow artifact-path collector with recursive traversal of every strict-v2 `relative_path`,
  `manifest_path`, and `log_path` in objects and arrays. All paths are validated as absolute, ordinary files beneath
  the same workspace backing directory before any JSON mutation or link creation; only then is the verified worktree
  `.vibris\artifact` link created and every field rewritten. Missing, cross-workspace, reparse-point, directory, and
  nonordinary targets retain fail-closed `ARTIFACT_LINK_ERROR` behavior with no partial rewrite.
- The screenshot fixture reproduces 692 expanded `changed_from_default` entries, proves the compact payload is at
  most 16 KiB with zero expanded settings or raw receipts, preserves the immutable full terminal bytes and result
  artifact hash identity, and retains exactly one screenshot and one manifest handle. Recursive fixtures cover
  nested results, action/prelude receipts, diagnostics, manifests, and matrix cases and prove zero backing-root
  leakage plus validation-before-mutation semantics.
- Visual Studio release builds passed for `vibris-synchronous-job-runner-tests`,
  `vibris-workspace-artifact-link-tests`, `vibris-job-protocol-tests`, and production `vibris-mcp`. Direct `all`
  execution passed for both focused executables, and
  `ctest --preset release -R 'StrictV2Result|ScreenshotResult|ArtifactLink|JobProtocol' --output-on-failure` passed
  6/6 tests. Static inspection found the screenshot projection contains no full settings/receipt collection and the
  path rewriter has exactly the three recursively traversed strict-v2 path keys with no old `artifacts[].path`,
  fallback, legacy, compatibility, or dual-shape branch.
- No deployment, Minecraft/launcher restart, live T20 request, Core storage change, or shader-worktree edit occurred.
  Protected Vibris `capture/a.spv`, Iris runtime directories, and every unrelated Alpha-Piscium dirty file remained
  untouched and unstaged. Commit: this task's commit with subject
  `T19B compact capture results and localize artifact paths`.

### T19C — Make the main-menu runtime ready for jobs

Status: `DONE`

Dependencies: T19B

Repository: `I:\code\Iris`

Worktree: `I:\code\Iris`

Branch: Iris `1.21.11-shaderdev`

Primary files:

- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\vibris\MinecraftVibrisRuntimeHost.java`
- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`

Scope:

- Report the initialized Iris runtime as connected while Minecraft is at the main menu, independently from whether a
  singleplayer world is loaded.
- Preserve the strict-v2 distinction between `minecraft_connected`, `world_loaded`, and `scene_applied`, so a main-menu
  runtime can start a job whose preset loads the requested world.
- Rebuild, redeploy, restart the authorized MultiMC instance, and prove live main-menu readiness before resuming T20.

Non-scope:

- Do not add a legacy readiness branch, protocol alias, fallback, or compatibility path.
- Do not modify shader worktrees or perform the remaining T20 acceptance matrix in this task.

Acceptance:

- A fresh main-menu runtime reports `minecraft_connected=true`, `world_loaded=false`, `scene_applied=false`, and
  `can_start_job=true`.
- The Iris compile/build passes.
- The deployed MCP and Mod hashes match their verified build outputs.

Verification:

- Iris Java compilation and Fabric build.
- Live strict-v2 `vibris_get_status` from the main menu after authorized restart.

Expected commit title: `T19C make main menu runtime ready`

Blockers:

- None; the user explicitly authorized redeployment and restart of the MultiMC `1.21.11-Iris` instance for this
  session.

Evidence:

- `2026-08-11`: T20 live probing proved the service port and preset catalog were available at the main menu, while
  status incorrectly returned `minecraft_connected=false`, `world_loaded=false`, and `can_start_job=false`. Source
  inspection found `VibrisBootstrap.start(...)` already runs during render-system initialization; the defect was
  `MinecraftVibrisRuntimeHost.status()` defining runtime connection as the simultaneous presence of level, player,
  and integrated server. The user requested a hard fix with no compatibility code before continuing T20.
- Replaced the Iris host's world-gated runtime connection flag with host-lifetime availability: the initialized host
  reports connected until `close()`, while `currentSaveId` and `currentDimensionId` continue to drive the independent
  world and scene readiness fields. No legacy branch, alias, fallback, or dual behavior was added.
- Focused `:vibris-core:test --tests dev.vibris.core.ServerDescriptorTest` passed against the corrected host contract.
- Iris `:common:compileJava :fabric:build --offline` passed on Java 21. The rebuilt 28,146,244-byte Mod was deployed
  to the authorized MultiMC instance with matching source/target SHA-256
  `6950BBE417E098E8657F6A776720ABE69C7E67FD5B5BAAFA1F006551444DE9B8`, then the instance was restarted as Java
  PID 29756.
- Before any world load, live strict-v2 status for AP4 workspace
  `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc` returned `SERVER_STATE_AVAILABLE`, `core_online=true`,
  `minecraft_connected=true`, `world_loaded=false`, `scene_applied=false`, `can_accept_job=true`, and
  `can_start_job=true`; transition 1 was `runtime-available`. The deployed MCP remained the already verified
  `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6` delivery.
- Iris external implementation commit: `3f3e458ea9fe904398bb28e4a8e05cb4c22e7afc` with subject
  `T19C make main menu runtime ready`; its parent is the recorded T16 Iris commit
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, it changes only
  `MinecraftVibrisRuntimeHost.java`, and it is the current `1.21.11-shaderdev` HEAD and an ancestor of that branch.
  This owner-repository receipt is ledger-only.
- `2026-08-11` receipt revalidation found the local and installed 28,146,244-byte Mod both at SHA-256
  `6950BBE417E098E8657F6A776720ABE69C7E67FD5B5BAAFA1F006551444DE9B8`; `config.toml` points to the
  15,368,192-byte MCP, whose configured delivery and source build both have SHA-256
  `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`.
  Java PID 29756 still owns `127.0.0.1:50051`, and a fresh main-menu status call reproduced the exact available,
  connected, no-world/no-scene, can-accept/can-start receipt above with no active source, jobs, or queue.

### T19D — Return typed live GPU metric receipts

Status: `DONE`

Dependencies: T19C

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ActionJobExecutor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\RuntimeActionProtocol.kt`
- Focused Core action-receipt, profile, matrix, and paired-benchmark tests

Scope:

- Preserve the actual JSON returned by `VibrisRuntimeAdapter.executeAction(...)` for `GET_GPU_METRICS` and convert it
  directly into the strict-v2 typed `GpuMetricsReceipt`, including sampled-frame count, timing unit, aggregate metric
  identities/statistics/samples, scopes, and program metadata required by profile and benchmark normalization.
- Apply the request's explicit metric-ID filter to the typed receipt and fail the action explicitly when live timing
  output is empty, malformed, or omits a requested metric; never report a successful empty receipt.
- Keep profile, profile-matrix, and paired-benchmark consumers on the one strict-v2 typed receipt path, then rebuild,
  redeploy, restart the authorized MultiMC instance, and prove one live AP4 profile exposes selectable metrics before
  T20 resumes.

Non-scope:

- Do not add legacy JSON fields, compatibility parsing, aliases, fallback metric names, synthetic timings, or a second
  result shape.
- Do not modify either shader worktree or claim the remaining T20 benchmark/statistical gate in this remediation.

Acceptance:

- A successful live `get_gpu_metrics` action contains a non-empty typed `gpu_metrics` receipt rather than `empty {}`.
- Missing/malformed/filtered-out live timing data produces an explicit failed receipt and structured error.
- Profile normalization returns the selected metrics and samples without retrying an OK-but-empty receipt.
- Focused Core and native profile/benchmark tests pass, and the deployed MCP/Mod hashes match their build outputs.

Verification:

- `.\gradlew.bat :vibris-core:test --offline --console=plain`
- Build and run the focused native strict-v2 profile, matrix, and paired-benchmark tests.
- In Iris, rebuild the Fabric Mod, redeploy both authorized artifacts, restart the existing instance, and run a live
  AP4 profile with the `night-gi-1-720p` preset.

Expected commit title: `T19D return typed GPU metric receipts`

Blockers:

- None known.

Evidence:

- `2026-08-11`: T20 live job `dd650914-eb9f-4abb-9c08-b5ae0953b7bf` completed a one-frame
  `get_gpu_metrics` action with `RECEIPT_STATUS_OK`, but its durable 146-byte result at
  `I:\code\mcshaders\Alpha-Piscium-4\.vibris\artifact\940cbe24-fd7d-39a8-86b9-a44595c282e5\437aceb1-4f22-39b7-b766-6e196c1ace0a\result.json`
  has SHA-256 `271294709FD6E37B253B9D03F68E4E8859857B630E8FF077D59540977219EF91` and contains only
  action index, kind, and status: there is no typed `gpu_metrics` payload. Longer 60/120-frame direct requests and
  profile retries reproduced the same OK-but-empty receipt.
- `CaptureActionExecutor.execute(...)` returns the real asynchronous `ShaderDebugControl.captureMetrics(...)` JSON,
  while `ActionJobExecutor` awaits and discards that returned string and unconditionally writes `EmptyReceipt` for
  every runtime action. The strict-v2 synchronous profile normalizer only consumes `receipt.gpu_metrics`, so it
  correctly cannot construct benchmark inputs from this live result. No compatibility path is permitted.
- Before discovering this defect, T20 live checks had already proved main-menu startup, shared fair queueing, both
  compile catalogs, settings provenance/restoration, an upright 720p screenshot, and exact texture/buffer boundaries;
  those partial receipts remain evidence only and T20 is not promoted until this remediation and its benchmark gate
  pass.
- The capture runtime now emits one canonical timing document with explicit `timingUnit=ns`, the exact requested
  sampled-frame count, real per-query samples on every aggregate and program statistic, aggregate scopes, and complete
  program identity metadata. Core preserves that returned document, validates the strict shape and scope/aggregate
  identity, converts aggregate and program entries to `GpuTimingMetric`, and applies only the request's explicit metric
  IDs. Empty samples, malformed fields, frame-count disagreement, duplicate typed identities, or an omitted requested
  metric fail the action with `ERROR_CODE_NO_GPU_SAMPLES`; no empty success, fallback metric, old field, alias, or
  second response shape remains.
- `2026-08-11`: `:vibris-capture:test :vibris-core:test --offline --console=plain` passed with Capture 26/26 and
  Core 105/105 tests, zero failures/errors/skips. Focused conversion/executor coverage proves the typed receipt,
  aggregate scope and program metadata, original samples, filtering, and explicit malformed/empty/omitted failures.
  Release native profile/matrix/paired-benchmark/JobProtocol targets built, and CTest filter
  `StrictV2Result|Profile|Matrix|Paired|Benchmark|JobProtocol` passed 13/13.
- The production MCP rebuilt to 15,368,192 bytes and matches the configured delivery at SHA-256
  `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`. The Java-21 Iris build passed with 41
  actionable tasks; the rebuilt and installed 28,158,449-byte Mod both have SHA-256
  `A3916EFC7F66198A5D6ADC7A8BB7CAAF5AA0783F7F632FBD66C751DBEA14264B`. The authorized MultiMC instance restarted
  as Java PID 51628 and returned available main-menu status before the live load.
- Non-restoring AP4 baseline job `4a4ab94e-f0d0-4d81-982b-5d84d30a3cc3` loaded
  `night-gi-1-720p` and established active source UUID `119400cf-b6fd-4060-8b45-24390f319c5a`. Live profile
  job/request `67149367-c986-4988-a7bf-4ba7e33a8b67` selected only `composite_total` and returned one typed receipt
  with 10 real samples, `average_ns=11232563`, `p50_ns=11128832`, and `p95_ns=12398387`. It used exactly one attempt
  with no empty-result retry. Its localized 200-byte result artifact has SHA-256
  `8D8C15EB7CE80B4CB6CF4CB859AEE57F53ABE143E52ED4EB6D10E346A3FF04DF`; the 1,157-byte manifest has SHA-256
  `8205CA30ED40A92C227B2984E86709F60CD42B8FAF4E38B3DE9C52D5C81948B6`.
- Final live status is available with an empty queue, `can_accept_job=true`, `can_start_job=true`, and the same AP4
  source active. The profile independently exposed incomplete provenance because the detached worktree's branch and
  live Minecraft version are empty; T19E is inserted before T20 rather than hiding that separate acceptance blocker.

### T19E — Define strict detached-worktree provenance contract

Status: `DONE`

Dependencies: T19D

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\BenchmarkProvenance.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt`
- `I:\code\vibris\proto\vibris_control.proto`
- `I:\code\vibris\mcp\src\main\cpp\source_preparer.cpp`
- `I:\code\vibris\mcp\src\main\cpp\synchronous_job_runner.cpp`
- Focused Core/source/provenance/profile tests

Scope:

- Define one required maintained host environment contract carrying exact Minecraft, Iris, GPU, OpenGL, and driver
  identity, and make Core provenance consume that contract directly instead of reflective probing or property values.
- Add an explicit strict-v2 attached/detached checkout state to prepared-source and result provenance. Require a real
  branch only for attached checkouts and require an empty branch plus exact HEAD for detached checkouts.
- Update source preparation, durable source projection, Core provenance, native normalization, and completeness gates
  on this one canonical shape.

Non-scope:

- Do not add reflective method probing, system-property or version fallbacks, placeholder versions, a synthetic named
  branch, aliases, legacy provenance fields, compatibility parsing, or modify Iris or either shader worktree.
- Do not deploy, restart Minecraft, execute the live profile, run the remaining paired benchmark, or claim T20
  acceptance; Iris implementation and live proof are owned by T19F.

Acceptance:

- The public/runtime host environment contract requires non-empty exact identity values and Core provenance contains
  only the host-supplied values with no reflective or property fallback.
- Source fixtures prove attached worktrees require a non-empty branch, while detached worktrees require an empty branch,
  explicit detached state, and exact immutable HEAD throughout preparation, durable projection, and result provenance.
- Strict-v2 profile/provenance completeness accepts the detached shape without weakening any other required field or
  accepting the old branch-only shape.

Verification:

- `.\gradlew.bat :vibris-api:test :vibris-core:test --offline --console=plain`
- Build and run focused native source/provenance/profile/JobProtocol tests.
- Static checks prove the affected production path contains no reflective environment probing, property fallback,
  synthetic branch, legacy field, or compatibility parser.

Expected commit title: `T19E define strict detached provenance contract`

Blockers:

- None known.

Evidence:

- T19D live profile `67149367-c986-4988-a7bf-4ba7e33a8b67` returned valid filtered typed metrics and restoration but
  `complete_result_provenance(...)` rejected the result. The terminal provenance shows `branch=""` for detached AP4
  and `environment.minecraft_version=""`; both fields are mandatory and T20 uses the same detached AP4/AP3 scope.
- `2026-08-11`: entry auditing proved the Minecraft version cannot be completed truthfully inside the Vibris-only
  task: `BenchmarkProvenance` currently reflectively probes Minecraft/Iris/GL classes, while the only maintained
  runtime host is implemented by Iris. The strict contract and VCS state therefore remain T19E Vibris work, and the
  direct Iris implementation plus deployment/live proof are split into external task T19F so each repository can land
  one atomic commit. No product source, deployment, process, configuration, or shader worktree was changed during this
  control-plane insertion.
- `2026-08-11`: added the required non-empty `RuntimeEnvironment` host/adapter contract and made every normal runtime
  job capture exactly those host-supplied values before provenance construction. Core no longer contains reflective
  Minecraft/Iris/OpenGL probing, system-property version inputs, implementation-version fallback, or placeholders.
- Added canonical `VcsCheckoutState` to prepared-source and result provenance. Native preparation now distinguishes
  attached versus detached directly from `git branch --show-current`; detached references retain an empty branch and
  exact 40-hex HEAD through queued snapshot projection, while Core rejects unspecified state, attached empty branches,
  detached non-empty branches, and detached non-exact HEADs. Strict native completeness accepts that detached shape and
  rejects the previous branch-only shape.
- Verification passed: API `9/9`, Core `108/108`, test-runtime compilation, focused native source/provenance/profile/
  JobProtocol/durable/commit/paired CTest `30/30`, and a final changed-path confirmation `5/5`. Static scans found zero
  affected production-path matches for reflection, property/version fallback, symbolic-ref branch fallback, checkout
  aliases, or compatibility parsing. No Iris/shader file, deployment, process, configuration, or live runtime changed.
- Commit: this task's commit with subject `T19E define strict detached provenance contract`.

### T19F — Implement and prove live detached-worktree provenance

Status: `DONE`

Dependencies: T19E

Repository: `I:\code\Iris`

Worktree: `I:\code\Iris`

Branch: `1.21.11-shaderdev`

Primary files:

- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\vibris\MinecraftVibrisRuntimeHost.java`
- Focused Iris Vibris bridge tests
- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md` in the later owner-repository receipt

Scope:

- Implement the T19E maintained environment contract directly from Minecraft `SharedConstants`, Iris version state,
  and current OpenGL driver strings on the client thread.
- Rebuild and deploy the current MCP and Mod, restart the already authorized MultiMC instance, and prove detached AP4
  and AP3 retain exact HEAD plus explicit detached state.
- Run one live AP4 `night-gi-1-720p` filtered profile and prove complete provenance, successful restoration, one
  attempt, and selectable typed T19D samples.
- Commit the Iris implementation first; on the next continuation record its full SHA and live receipts in this ledger
  through the owner-repository receipt commit.

Non-scope:

- Do not add reflection, version/property fallbacks, placeholders, synthetic named branches, aliases, legacy fields,
  compatibility parsing, or modify either shader worktree.
- Do not execute the remaining paired benchmark or claim T20 acceptance.

Acceptance:

- Runtime provenance contains the exact non-empty Minecraft version and other required identity directly supplied by
  the maintained Iris host contract.
- Detached AP4/AP3 sources retain their exact HEAD and explicit detached state without masquerading as a branch.
- The live filtered AP4 profile passes provenance and restoration with one attempt and non-empty selectable typed GPU
  samples; no provenance gate is bypassed or weakened.
- Iris compile/build and focused bridge tests pass, deployed MCP/Mod hashes match their verified build outputs, and the
  external full Iris commit SHA is recorded in the later owner receipt.

Verification:

- `.\gradlew.bat :fabric:vibrisBridgeTest :common:compileJava :fabric:build --offline --console=plain`
- Authorized MCP/Mod deployment and MultiMC restart.
- Live AP4/AP3 source preparation checks and an AP4 `night-gi-1-720p` filtered profile.
- SHA-256 verification of deployed artifacts and protected Git-state checks.

Expected commit title: `T19F complete live detached provenance`

Blockers:

- None.

Evidence:

- Inserted after T19E contract auditing proved truthful environment provenance requires a direct Iris host
  implementation and therefore cannot share an atomic commit with Vibris protocol/Core/native changes.
- External Iris commit `5c5909726df7e39dcf35e1199d27aacd6ab64cf2` (`T19F complete live detached
  provenance`) has parent `3f3e458ea9fe904398bb28e4a8e05cb4c22e7afc`, is an ancestor of
  `1.21.11-shaderdev`, and changes exactly `common/build.gradle.kts`, the maintained
  `MinecraftVibrisRuntimeHost`, two existing bridge tests, and new `MinecraftVibrisRuntimeHostTest`. The host now
  supplies exact Minecraft, Iris, Vibris, Java, OS, GPU vendor/renderer, OpenGL, and driver identity on the client
  thread with no reflection, property/version fallback, placeholder, alias, or compatibility path.
- `.\gradlew.bat :fabric:vibrisBridgeTest :common:compileJava :fabric:build --offline --console=plain` passed twice;
  the retained bridge XML reports `17/17`, zero failures, errors, or skips. The built and installed Mod are both
  28,166,451 bytes with SHA-256 `68027918BD4F9A04D56937C765A111CA541BB5577AB8E074F247ADF033A93858`.
  The release and configured delivery MCP executables are both 15,369,728 bytes with SHA-256
  `6F791BDAD241A793C082BCC1F2434B1D107F5EE204E6E70A22F58136343A70CF`; `config.toml` selects the delivery copy.
- Authorized restart produced Java PID 28656 listening on `127.0.0.1:50051`. The deployed runtime was already
  available at the main menu before any world load, and the current eight-tool v2 MCP now reports both AP4 workspace
  `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc` and AP3 workspace
  `90485cf5-12a0-45b9-bb89-7141a9b7ee1e` as `SERVER_STATE_AVAILABLE`, with empty queue/jobs and both accept/start
  true.
- AP3 verification job/request `76a53037-5b7a-481f-99ae-e5f0cfde5b75` retained explicit
  `VCS_CHECKOUT_STATE_DETACHED`, empty branch, and exact start/completion/resolved HEAD
  `9325c7a091647a3d8243720d06802bdc2640292e`; source SHA-256 matched
  `d3ca7a48b7589b9b185ab3c9357364f3817776de138c163851a170d338153e65`, restoration passed, and manifest ID was
  `44f5af69-78bd-358a-8b0f-6cb958e528ce`. AP4 baseline job/request
  `180b9b70-c2a1-4585-93d6-f4e1fc4b0571` likewise retained detached HEAD
  `b793f75bc411b309142305ce062e17bc52b259c3` with complete environment, restoration, and manifest
  `d838b69e-95f3-3ba0-bfe2-84d1b171375b`.
- Required AP4 profile job/request `0f8d20d6-4a98-4472-8b91-9006aecff159` passed
  `night-gi-1-720p` in one attempt with no retries, exact detached start/completion/resolved HEAD
  `b793f75bc411b309142305ce062e17bc52b259c3`, successful restoration, and ten selectable `composite_total`
  nanosecond samples: average `13,306,163`, p50 `12,403,200`, p95 `18,384,947`. Its environment was Minecraft
  `1.21.11`, Iris `1.10.6-snapshot+mc1.21.11-local`, Vibris `0.0.1-SNAPSHOT`, Java
  `25+37-LTS-jvmci-b01`, `Windows X64`, NVIDIA RTX 3080 Ti, and OpenGL/driver
  `3.3.0 NVIDIA 596.36`; manifest ID was `e7efdf3f-347f-3445-8f5a-e84a3d592857`.
- Final entry audit preserved every declared worktree HEAD, empty staging area, and all recorded Iris, AP2, AP4,
  AP4a/AP4b, AP6, AP7, runtime, and untracked user-owned state. No shader source was modified or staged.

### T19G — Repair live paired-benchmark finalization

Status: `DONE`

Dependencies: T19F

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `mcp/src/main/cpp/paired_benchmark.cpp`
- `mcp/src/main/cpp/profile_matrix_workflow.cpp`
- `mcp/src/main/cpp/synchronous_job_runner.cpp`
- Focused paired-benchmark, durable-workflow, JobProtocol, and synchronous-runner tests

Scope:

- Preserve the exact explicit `preset_id` through paired-benchmark profile/visual children and any retry path so every
  nested strict-v2 Core job carries the same preset identity as the outer request and returns complete provenance.
- Normalize protobuf-JSON integer scalars exactly once at the maintained strict-v2 native boundary before typed
  benchmark guards consume them. The native paired-benchmark representation must use one canonical numeric shape for
  compile-catalog generations, GPU timing samples, frame IDs, timestamps, sizes, and any other typed integer it reads;
  do not scatter dual-shape parsing through benchmark logic.
- Make finalization-only failure safely resumable when every planned step already has an immutable receipt: rebuild and
  publish the final result from those receipts with zero child executor/Core calls and zero side-effect replay. Do not
  make arbitrary non-retryable step failures resumable.
- Keep visual comparison fail-closed. A receipt that fails the configured pixel/SSIM thresholds remains a failed gate;
  neither finalization recovery nor normalization may weaken, skip, or reinterpret the thresholds.

Non-scope:

- Do not add aliases, legacy fields, fallback readers, compatibility adapters, migrations, or accept two maintained
  native result shapes.
- Do not weaken benchmark, provenance, restoration, statistical, compile, or visual gates; do not modify either shader
  worktree or continue T20 captures in this task.
- Do not execute T99.

Acceptance:

- Every nested paired-benchmark measurement and visual request contains the exact non-empty outer `preset_id`; its live
  normalized result has complete provenance with that same ID and preset hash.
- A live-shaped strict-v2 fixture containing protobuf-JSON integer strings such as compile-catalog
  `shader_generation="35"` is normalized once and produces a deterministic paired result without a JSON type exception.
- A durable job with `next_step == completed_steps == total_steps`, all receipt files present, no accepted in-flight
  request, and no result file can resume finalization, publish atomically, and prove the executor/Core call count stayed
  zero. A non-checkpointed non-retryable failure remains non-resumable.
- The recorded AP3/AP4 visual receipt with SSIM `0.8143305138814317` and threshold-pixel ratio
  `0.9270258246527778` still produces `VISUAL_GATE_FAILED`/`GATE_FAILED`; no threshold is relaxed.

Verification:

- Release build of the paired-benchmark, profile-matrix/durable-workflow, synchronous-runner, and JobProtocol targets.
- Focused CTest coverage for nested preset provenance, canonical integer normalization, fully-checkpointed
  finalization-only resume, non-resumable unsafe failures, and fail-closed visual thresholds.
- After verified deployment, one focused live `night-gi-1-720p` benchmark proof that publishes a terminal result from
  complete receipts and restores the original AP4 source/settings/scene state.
- Ledger checker, staged-diff check, and protected Git-state audit.

Expected commit title: `T19G repair live paired benchmark finalization`

Blockers:

- None. Session authorization for MCP/Mod deployment and the existing MultiMC restart remains active; restarting Codex
  remains user-owned.

Evidence:

- T20 durable job `76e06187-da08-4e96-9df9-d3edc6e1195d` completed and checkpointed all 17 planned steps, then
  final-result publication changed the state to `paused` with `next_step=completed_steps=total_steps=17`, null current
  step, no `result.json`, and non-retryable `INTERNAL_ERROR: [json.exception.type_error.302] type must be number, but
  is string`. Its request/state SHA-256 values are
  `FEFE9533A5A34A6746251D9F98E8162AAA7D8BAE215272DC00B0B26D5D3E6899` and
  `4D217707D1667C5FB9F66F6168EB559ED0E75BA0E64AF6A1A41F1AEB60B2F37C`.
- Receipt `00000000.json` is 284,221 bytes with SHA-256
  `9375117E8637BDF36EC1E3B90581AB2B6463F60497D30F7D43F3771ACDD866FE`. Its child arguments retain
  `__vibris_preset.preset_id=night-gi-1-720p` but omit `preset_id`, so JobProtocol submits an empty preset ID while
  retaining preset SHA-256 `d3d37c2f3d751464214223d06ddd8b8924a54ac28be978608fd7eff5ea16dece`.
  All 16 profile receipts therefore report `INCOMPLETE_PROVENANCE` despite successful Core execution and restoration.
- The same receipt proves the exact type boundary: its raw strict-v2 compile catalog stores protobuf-JSON
  `shader_generation` as string `"35"`, while `compile_catalog_passed` consumes that field as native `uint64_t`.
  GPU timing scalars are likewise raw strings but already become native integers in the normalized case. The final
  replay reaches the unnormalized compile-catalog generation and throws before a benchmark verdict can be published.
- Visual receipt `00000016.json` is 284,791 bytes with SHA-256
  `72C08A31D7F7B513B28C18C9D0465320B84400932886611E8BC9B8139B53450B`. It restored successfully but correctly
  returned `invalid_comparison`, SSIM `0.8143305138814317`, threshold-pixel ratio `0.9270258246527778`, and
  `comparison.passed=false`; this evidence must remain a failed visual gate after remediation.
- Final live status is `SERVER_STATE_AVAILABLE` with empty queue, both accept/start true, and AP4 source UUID
  `59b584ca-1698-45a1-9861-c67e3821c19a`, proving the finalization defect did not strand runtime ownership or prevent
  restoration. No shader source was modified or staged.
- Paired profile, visual, matrix, and retry plans now copy the required outer `preset_id` exactly. JobProtocol and all
  result mappers share one reflection-based strict-v2 protobuf boundary that converts every signed/unsigned 64-bit JSON
  scalar to the canonical native number shape; downstream benchmark readers no longer accept the string form.
- Durable workflow tests prove a fully checkpointed finalization-only state publishes from its 17 immutable receipts
  after restart with zero executor calls, while an unsafe non-retryable step failure remains non-resumable. The recorded
  SSIM `0.8143305138814317` and threshold-pixel ratio `0.9270258246527778` fixture still returns `GATE_FAILED`.
- Release builds passed for the paired-benchmark, durable-workflow, synchronous-runner, JobProtocol, and production MCP
  targets. Focused CTest passed 20/20 scenarios, covering nested preset identity, retry preservation, native integer
  normalization, finalization-only resume, unsafe failure rejection, and fail-closed visual thresholds.
- Deployed MCP `I:\code\vibris\build\delivery-t19g-51748267-68027918\vibris-mcp.exe` is 15,376,896 bytes with
  SHA-256 `5174826772A93B6169E81BDFDC2F85AD30235B14DA48A2EB224969BDA060FCEE`; the installed Iris Mod remained the
  already-current 28,166,451-byte artifact with SHA-256
  `68027918BD4F9A04D56937C765A111CA541BB5577AB8E074F247ADF033A93858`.
- Live durable job `52d66814-4469-4090-ac5f-5482c370fc94` published `result.json` after 17/17 immutable successful
  receipts. All request steps and returned provenance use `night-gi-1-720p` with preset SHA-256
  `d3d37c2f3d751464214223d06ddd8b8924a54ac28be978608fd7eff5ea16dece`; all 16 profile cases passed, every
  restoration receipt is `RECEIPT_STATUS_OK`, and no `INCOMPLETE_PROVENANCE` or quoted `shader_generation` remains.
  The receipt-set SHA-256 is `C4C93E203701E1E958CA4E095F96C4C107D4F316483061E52AB055A1E7FD5445`.
- The live visual comparison remained fail-closed at SSIM `0.868573744827144` and threshold-pixel ratio
  `0.9129741753472222`, producing `VISUAL_GATE_FAILED` and terminal `GATE_FAILED`; configuration/provenance guards also
  truthfully rejected the different AP3/AP4 effective settings. The workflow emitted `finalizing` before `completed`,
  then live status proved `SERVER_STATE_AVAILABLE`, an empty queue, both accept/start true, and exact AP4 source UUID
  `59b584ca-1698-45a1-9861-c67e3821c19a` restored. Request/state/result/events SHA-256 values are
  `E097B083D05CABBF2A35DDD46F686671D6561535750B73EA4D6A629989F21084`,
  `0EC71E09DAF4D655BA1D36D8DB496DE92D4DA0F899BF218C962052698A1B3AB6`,
  `C8194FD63B7DEAEB1CF871C9534EB39002EE41B2606AE585E82DA24492DBCF00`, and
  `7BB9DF9CB9304E2BE4A94733529345CF0FADEDEE949A1D146838FE9563C67126`.

### T19I — Add a frame-atomic deterministic temporal capture phase

Status: `DONE`

Dependencies: T19G

Repository: `I:\code\vibris` and `I:\code\Iris`

Worktree: `I:\code\vibris`; `I:\code\Iris`

Branch: Vibris `main`; Iris `1.21.11-shaderdev`

Primary files:

- `I:\code\vibris\api\src\main\kotlin\dev\vibris\api\VibrisRuntimeAdapter.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ActionJobExecutor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\RuntimeJobExecutor.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt`
- `I:\code\vibris\core\src\main\kotlin\dev\vibris\core\RenderedFrameClock.kt`
- Focused Vibris API/Core executor, frame-clock, and thread-bound adapter tests
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\vibris\MinecraftVibrisRuntimeHost.java`
- `I:\code\Iris\common\src\main\java\net\irisshaders\iris\uniforms\SystemTimeUniforms.java`
- Focused Iris host and deterministic-time tests
- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`

Scope:

- Replace the separate strict-v2 load/reset/wait/capture runtime handoffs used by an adjacent paired visual case with
  one required hard-cut operation that covers the final context/reload, temporal reset, warmup anchor, and capture
  target. After Core activates the prepared source, the client-thread operation must finish context application and
  shader reload, clear/recreate pack temporal GPU state, enter the deterministic phase, record one rendered-frame
  anchor, and pre-register the capture for exactly `anchor + warmup_frames + 1` before the client thread can advance.
- Add an absolute-target capture primitive to `RenderedFrameClock`. A missed target, cancellation, or failure must
  fail closed; it must never fall back to an unanchored wait or a later next-frame capture.
- Preserve the existing strict-v2 logical load/reset/wait/capture action indices and typed receipts by deriving them
  from the compound result, including exact start/end/capture frame IDs. Other action sequences retain their existing
  semantics.
- During the compound phase, make Iris shader-visible `frameCounter`, `frameTime`, and `frameTimeCounter` advance from
  one deterministic origin at a fixed logical step. Exit that mode in `finally` on success, failure, or cancellation
  so ordinary gameplay and restoration use the real clock.
- Land this one logical task as coordinated, separate repository commits without a compatibility bridge: Vibris
  contract/Core first (`T19I add frame atomic temporal capture contract`), Iris required implementation second
  (`T19I implement deterministic temporal capture phase`), then a Vibris owner-receipt commit after direct live proof.
  T19I remains `READY` between those commits so each Goal continuation still creates at most one atomic commit.

Non-scope:

- Do not weaken or recalibrate any visual threshold, reuse a capture, change a shader source or supplied shader
  worktree, add a default interface method, preserve the old disjoint path as a fallback, or introduce a v1/legacy
  adapter.
- Do not treat a reset completion timestamp as a frame anchor, and do not claim that a later observed
  `capture_frame=end_frame+1` proves an atomic boundary.
- Do not stage or commit the retained T19H MCP planner/guard/test files while executing T19I. They may be used only to
  build the direct live proof and remain owned by T19H until its own acceptance passes.
- Do not alter Codex MCP configuration or restart Codex. Invoke each newly built MCP executable directly. A task-owned
  Mod deployment and restart of the authorized MultiMC instance are allowed when the Iris implementation requires it.

Acceptance:

- Focused Core interleaving tests advance frames between the formerly separate operations yet prove warmup begins at
  the reset anchor and capture occurs at exactly `end_frame + 1`; an already missed absolute target fails closed.
- Normalized receipts preserve the original load/reset/wait/capture kinds, indices, status, and exact frame range, and
  cancellation or failure cannot publish a successful capture or leave deterministic mode active.
- Iris tests feed different real nanosecond sequences but produce identical deterministic `frameCounter`, `frameTime`,
  and `frameTimeCounter` sequences. Success, cancellation, and failure all restore real-time mode.
- At least two consecutive direct-executable same-clean-commit comparisons of the authorized deterministic target under
  `night-gi-1-720p` pass every unchanged threshold, including `max_threshold_pixel_ratio=0.001`, with distinct
  physical capture frames, matching source/config/scene hashes, verified artifacts, and successful restoration. The
  default target remains clean `Alpha-Piscium-8` branch `1.10/fsr3` at exact HEAD
  `0c4112620b15dfd3b7684221714f58bda4fb6439`; when its unchanged shader-internal race prevents the gate, use the
  user-authorized clean `I:\code\mcshader` commit containing only the minimal compute shader that writes the fixed
  color pattern directly. A deliberately different fixture still fails closed.
- Final runtime status is available with the entry source restored, no queued or active jobs, and both accept/start
  true. Codex MCP deployment/configuration remains byte-for-byte untouched.

Verification:

- Focused Vibris API/Core Gradle tests covering the compound operation, thread-bound execution, absolute frame target,
  receipt normalization, cancellation, and failure.
- Focused Iris common/Fabric build and deterministic-time/host tests.
- Focused native T19H protocol/visual/paired-benchmark tests against the coordinated builds.
- Two consecutive direct live same-commit `Alpha-Piscium-8` `1.10/fsr3` `night-gi-1-720p` comparisons with artifact
  SHA-256 verification, followed by status, configuration, process, and protected Git-state audits.
- `git diff --cached --check`, staged names/stat/full diff, and exact post-commit SHA/subject checks separately in each
  repository.

Expected commit titles:

- Vibris product: `T19I add frame atomic temporal capture contract`
- Vibris hardening continuation: `T19I fail closed deterministic cleanup and recovery`
- Iris product: `T19I implement deterministic temporal capture phase`
- Vibris owner receipt: `T19I record deterministic temporal phase proof`

Blockers:

- The supplied Alpha-Piscium 1.10/1.9 lines remain unsuitable for the unchanged visual gate because their unchanged
  `EnvProbeUpdate1ReprojectScatter.comp.glsl` dispatches 512x2x3 workgroups and performs a plain `imageStore`
  through `persistent_envProbeTemp`; multiple invocations can target one texel, and GL barriers cannot impose an order
  inside that dispatch. This blocker is resolved for T19I by the user's `2026-08-13` acceptance-scope change: create a
  separate clean `I:\code\mcshader` Git worktree containing only a minimal compute shader that writes deterministic
  colors directly, then use its exact commit for the same-source proof. Existing shader worktrees, thresholds, and
  compatibility boundaries remain unchanged.

Evidence:

- `2026-08-12` T19H's planner correction built successfully and passed all five focused native CTest scenarios plus
  the complete job-protocol, synchronous-runner, and paired-benchmark test binaries. The direct MCP executable is
  15,378,432 bytes with SHA-256 `ED0316C991FD0C18C037144A5D3B3D3A4FF7D21EC1782494B58D9DC0CFBDFBDC`.
- Direct job/request `201000d7-f086-4eaf-a70d-596597be4e93` used clean AP4 commit
  `b793f75bc411b309142305ce062e17bc52b259c3` on both sides under exact `night-gi-1-720p`. Its action receipts were
  ordered reset 0, wait 1, capture 2, reset 3, wait 4, capture 5, compare 6. The A range was 204014-204134 with
  capture 204135; the B range was 204146-204266 with capture 204267. Both reset receipts were successful and all
  deterministic/provenance guards passed.
- The comparison still failed the unchanged threshold-pixel-ratio gate at
  `0.2518901909722222 > 0.001`; SSIM `0.9990051277007846`, MAE `0.0033822252860575574`, RMSE
  `0.007031817111295989`, P95 `0.011764705882352941`, and maximum error `0.23921568627450981` each passed. An earlier
  corrected job `0dc85d09-31b9-4854-8809-5ee99ddec6e7` independently failed at ratio `0.150475260`, so the result is
  not a single-capture anomaly.
- Iris currently resets CPU counters, then resumes `frameTime`/`frameTimeCounter` from wall-clock deltas; AP4 consumes
  those uniforms in exposure, GI/DOF, wind, and water paths. Core also completes reset, registers the frame wait, and
  schedules capture as separate asynchronous operations. A frame may therefore advance temporal buffers outside the
  counted warmup, and a successful reset timestamp is not a rendered-frame boundary.
- Manifest `81d06672-94ad-3db8-b846-5f9f837d4fb1` and artifacts are rooted at
  `I:\code\mcshaders\Alpha-Piscium-4\.vibris\artifact\24e171a5-6210-3cf2-a727-49109206617d\6e1e0814-ed54-3340-b2db-029207ec727d`.
  A/B PNG SHA-256 values are `5222A79B78D0E8787C8FF8DD124EC67C6ADA7DB7033AABECD27C78C5ED32F174` and
  `2F8DA7B4E0475B84FE7E56DC14612002CADFC794309929F4AB96177AE408FF10`. Restoration returned exact source UUID
  `d790f3fd-ca41-4883-b302-70bd958dd5a1`; final status was available with an empty queue and no active job.
- Vibris entered this continuation at `97e480f0b49c8dd36125ee305569c0068f40f8d4` and Iris at
  `5c5909726df7e39dcf35e1199d27aacd6ab64cf2`. The six focused T19H product/test files are intentionally retained
  unstaged, `capture\a.spv` remains untouched, no shader source was changed, and Codex MCP configuration/deployment was
  not modified.
- The legacy ledger checker named in older task text is absent from both the user-invoked and current installed skill
  paths. An equivalent structural audit found 33 unique task rows and 33 matching detail headings with no duplicates
  or omissions: `READY=1` (`T19I`), `BLOCKED=2`, `PENDING=1`, and `DONE=29`.
- `2026-08-12` the Vibris product phase hard-cut the strict load/reset/wait/capture block to one required compound API.
  Final context and reload now precede one synchronous planner call against the authoritative resource and compile
  catalogs; the same client task then resets temporal state and atomically registers capture at
  `anchor + warmup_frames + 1`. There is no old overload, default method, granular fallback, or compatibility bridge.
- Core now JIT-plans every catalog-dependent capture after `LOAD`, `ACTIVATE`, or another compound block. Reservation
  growth, global output-name checks, comparisons, after-pass captures, rollback, exact logical action indices, typed
  partial receipts, and zero-artifact failure receipts all use the post-mutation catalog and fail closed.
- Absolute scheduling, cancellation, close, missed-target, restoration, activation-commit attribution, multi-block,
  zero-warmup, source-attribution, terminal-frame, exact resource-descriptor, and fake-runtime concurrency tests passed.
  Full API/Core/test-runtime tests passed with the complete Core suite, and the offline Gradle `build` passed after
  rebuilding only the existing native descriptor-dump target required by the descriptor-parity integration test.
- This Vibris product continuation entered at `4bccda4c148e37892ae563046e7a14c502398ff8`.
- T19I remains `READY`: the required Iris host and deterministic shader-time implementation is the next coordinated
  commit, followed by direct-executable runtime proof and the Vibris owner-receipt commit. No Mod or MCP was deployed,
  Codex configuration was not changed or restarted, Minecraft was not restarted, the six retained T19H MCP files stay
  unstaged, and `capture\a.spv` remains untouched.
- `2026-08-12` direct same-clean-commit AP4 job `1bccc8df-a841-4b26-a611-71d3e9890b15` used exact
  `night-gi-1-720p`, 120 warmup frames, unchanged `max_threshold_pixel_ratio=0.001`, distinct capture frames
  `2406`/`2657`, matching source/config/scene provenance, and successful restoration. It failed closed at ratio
  `0.015547960069444445`; maximum error was `0.4549019607843137` and SSIM was `0.9998052931309492`.
- Temporary client-thread readback diagnostics at those two captures proved the CPU/GPU input set was identical before
  allocation: scene signatures matched; shader-visible counter/time were exactly `121`/`2.0166655`; SSBO 3 allocation
  counter SHA-256 was `4ee5ad0c4071781ea6c5b7884fa232c2b1887007ac21a87c39c8ba62ba0cc98b` on both sides;
  occupancy was `254ce1ce3bb53dfdfd07fd1f02d7297c104cff0a3ca83a58e49c42f8e2801c74` on both; and bucket
  prefixes were `d931f6f8b4771a7ed4924f5650a12f4054be04ed9c75e39b20b24a723e2f51f0` on both. Only the
  allocation-ID range diverged (`d54f8eaa69c7ef77c4230b920d0ace5228040765190f736f1fcc4f6b4d7e193d` versus
  `becc4ee4e9bf6a4debc430baf79757ee708e56bfea0617e8f3dcf80c81a38da4`), followed by divergent
  SSBO 10/11 radiance-cache hashes. Earlier direct job `7e476935-a0b0-49da-a359-616df770e338` independently failed
  the same gate at ratio `0.022518446180555554` with matching provenance and restoration.
- The diagnostic readback/logging and the disproven early deterministic-time experiment were removed. Focused
  `:common:compileJava`, `:fabric:vibrisBridgeTest`, and `:fabric:remapJar` passed. The clean Mod was deployed with
  matching source/instance SHA-256 `F46CCC774C9A6AB6BAC085A442AF423C3C2EEAEB6DC242781A97CE58AC0C03D1`.
  Baseline restore job `9a515896-0e52-4545-8b0d-de6b89dd521a` left active source UUID
  `b7c0151f-8355-4640-b2d7-5d91b162992f`; final status was available with an empty queue, no active job, and both
  accept/start true. AP3/AP4 shader files, Codex MCP deployment/configuration, retained T19H files, and `capture\a.spv`
  were not modified.
- `2026-08-12` blocker audit 2 re-read the complete ledger and reverified every repository/worktree identity, HEAD,
  branch, and protected dirty boundary. AP4 still assigns persistent brick allocation IDs through the same
  cross-workgroup `atomicAdd(voxel_bucketCounts[dist], 1u)` at clean detached HEAD
  `b793f75bc411b309142305ce062e17bc52b259c3`; no authorization has changed the no-shader-change/no-state-reuse
  acceptance contract. The queue therefore still has no `READY` task, and no product code, deployment, process, or
  runtime job was changed.
- `2026-08-12` blocker audit 3 re-read the complete ledger and reverified Vibris `main` at
  `253b93563af036ab1fef61d641f0cec9a54f3014`, Iris `1.21.11-shaderdev` at
  `bf5055039e134d3bcca8d4801e0091c3eb33d9ca`, all declared review/runtime/shader worktrees, empty staging areas, and
  every protected dirty boundary. AP4 remains detached at the supplied immutable commit
  `b793f75bc411b309142305ce062e17bc52b259c3`, and its allocator still assigns IDs through the unchanged
  cross-workgroup `atomicAdd(voxel_bucketCounts[dist], 1u)`. No scope authorization permits changing that shader or
  reusing GPU state, so `T19I`, `T19H`, and `T20` remain `BLOCKED`, `T99` remains `PENDING`, and the queue has no
  `READY` task. No product code, shader, deployment, process, configuration, artifact, or runtime job was changed;
  after this ledger-only atomic checkpoint the Goal is marked `blocked` on its third consecutive identical audit.
- `2026-08-12` scope-resolution audit verified the user's replacement target without creating a worktree:
  `I:\code\mcshaders\Alpha-Piscium-8` is clean on local branch `1.10/fsr3`, exact HEAD
  `0c4112620b15dfd3b7684221714f58bda4fb6439`, one commit ahead of its remote tracking ref. `git ls-tree` found no
  tracked path containing `voxel`, and exact tree searches found neither
  `atomicAdd(voxel_bucketCounts[dist], 1u)` nor the AP4 allocator file. T19I is therefore restored as the sole
  `READY` task; T19H and T20 return to ordered `PENDING`. No product/shader file, deployment, process, configuration,
  artifact, or runtime job changed in this control-plane continuation.
- `2026-08-12` T19I Vibris hardening closed the deterministic sequence boundary around every attempted compound capture:
  a failed begin still receives one cleanup attempt, cleanup failure is primary with the body failure suppressed, and
  the unresolved cleanup barrier is retained as a recovery prerequisite. Recovery now waits for that barrier before
  restoring the safe snapshot, while ordinary jobs already queued when recovery begins are rejected before entering the
  runtime. New regressions cover failed-begin cleanup, cleanup-failure precedence/barrier waiting, and the queued-job
  recovery gate. The focused API/Core/test-runtime suite passed 216/216 with zero failures, and offline `build` passed;
  T19H's six protected MCP files, `capture\a.spv`, Iris, shader worktrees, deployment, and Codex configuration remain
  untouched. The atomic continuation commit title is `T19I fail closed deterministic cleanup and recovery`.
- `2026-08-12` Iris product commit `a58f107f3a7e77d8447ba04998e9ae49f39e12a0` implemented the frame-origin deterministic
  `frameCounter`, `frameTime`, and `frameTimeCounter` phase with focused `:common:compileJava`,
  `:fabric:vibrisBridgeTest`, and `:fabric:remapJar` passing. The built and installed Mod jars match at SHA-256
  `E6BB181CCF9A539582B542F3301D5E1DB778EE1FA851CFE960F559FED5791890`; the six retained T19H files, `capture\a.spv`,
  and Iris runtime directories remained outside the commit.
- Direct same-clean-commit AP8 job `cc5c926c-c593-49aa-a4ea-e65d68ef84cf` used exact commit
  `0c4112620b15dfd3b7684221714f58bda4fb6439`, preset `night-gi-1-720p`, and 120-frame warmups. Distinct captures
  `52002`/`52222` matched source snapshot `c9da2530f499317e4a6b61a5b9206842b705a2a5a15d9fafa23949ccc02b74cf`,
  config `d08a39a3e7055aadbfb77b2c192a7ea777841d3c4f22121b0979ecd0cb033c8f`, and scene
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`; restoration and all non-visual guards passed.
  The unchanged visual gate failed only `threshold_pixel_ratio`: `0.003959418402777777 > 0.001` (MAE
  `0.00039572908156284886`, RMSE `0.0013625340985276787`, P95 `0.00392156862745098`, maximum
  `0.058823529411764705`, SSIM `0.9999831940518547`). The manifest was deleted after its receipt was recorded.
- A second direct same-clean-commit AP8 job `a9a4d806-5b08-41d9-9756-f9a0e09eb6d7` repeated the same exact
  provenance and 120-frame protocol at distinct captures `59279`/`59510`. Restoration and all provenance/config/scene
  guards again passed, while the unchanged visual gate again failed only `threshold_pixel_ratio` at
  `0.006438802083333334 > 0.001` (MAE `0.00047570082720548013`, RMSE `0.0015503486988184968`, P95
  `0.00392156862745098`, maximum `0.1803921568627451`, SSIM `0.999975342281981`). Its manifest and artifacts were
  deleted after the receipt was recorded; final AP8 status was available with an empty queue and both accept/start
  true.
- Two one-frame `iris_custom_image.uimg_frgba16f` debug captures (`c4a6af41-0915-400e-ac62-f8d70e74c3c0` and
  `059082c8-d6d4-46bc-923a-7850605ddb9f`) were byte-identical at SHA-256
  `579a56fff58abb8b6d25c732dd17ec5c2e5057c624df78416f4f9f87d499543d`, while prior 120-frame readbacks differed
  only inside the fixed `envProbeTemp` atlas region. This localizes the divergence to the unchanged AP8 scatter
  shader rather than source, preset, scene, restoration, or Iris window/process state. No shader or Codex MCP file was
  modified, and no Minecraft/MultiMC window was brought to the foreground.
- `2026-08-13` read-only audit of the user-authorized 1.9 alternative `I:\code\mcshaders\Alpha-Piscium` at branch
  `1.9/dev`, exact HEAD `8a15b72cc242a3ee1ac1a8c5e329c30aa06df073`, found no tracked voxel path but the same
  `EnvProbeUpdate1ReprojectScatter.comp.glsl` 512x2x3 dispatch, plain `persistent_envProbeTemp_store`, and active
  `composite2.csh` inclusion. Its pre-existing untracked changelog/property files were not read or modified. The
  currently available authorized 1.9 and 1.10 lines therefore share the same shader-internal race; no clean alternate
  target is available without a new user-supplied scope decision.
- `2026-08-13` scope-resolution audit: the user authorized a new synthetic target under `I:\code\mcshader`. The next
  T19I continuation must initialize that path as a clean local Git worktree, add only a minimal compute-shader package
  that writes a fixed deterministic color pattern, record its exact commit and source snapshot, and run the unchanged
  two-consecutive same-commit temporal proof against that target. This is a new validation fixture, not a modification
  or compatibility fallback for either Alpha-Piscium line; all declared repositories, protected dirty files, and Codex
  MCP configuration remain outside its scope.
 - `2026-08-13` entry-gate recheck: Vibris `main` is `216dfc66f8b7f77b165e62833b29fd532632c23c`, Iris
   `1.21.11-shaderdev` is `a58f107f3a7e77d8447ba04998e9ae49f39e12a0`, AP8 remains clean at
   `0c4112620b15dfd3b7684221714f58bda4fb6439`; the previously selected 1.9 source was recorded at
   `8a15b72cc242a3ee1ac1a8c5e329c30aa06df073` with its recorded user files, while its current branch drift is listed
   below. All declared review/runtime/shader
   worktrees and protected files remain outside staging. Direct strict-v2 status for AP8 workspace
   `410c59d7-a344-449a-a05f-5f1ea4c2d944` and 1.9 workspace `15e0ddd4-2df5-4b59-95da-9ef83f23416f` is
   `SERVER_STATE_AVAILABLE`, `minecraft_connected=true`, `world_loaded=true`, `scene_applied=true`,
   `can_accept_job=true`, `can_start_job=true`, with empty queue/jobs. The legacy checker path is absent; the
   equivalent structural audit reports 33 task headings/status rows, `READY=1`, `BLOCKED=0`, `PENDING=3`, `DONE=29`.
 - The same entry audit found external user-side worktree drift outside this task: `I:\code\mcshaders\Alpha-Piscium`
   is now branch `1.10/atmo-optimize` at `1a6c1d1026ce607aebc91c9d3ec4e6f7b5d56101` with its recorded changelog/
   property files; `Alpha-Piscium-2` retains tracked `TranslucentBackComposite.glsl` dirt and untracked
   `scripts/kernel-sharing`; `Alpha-Piscium-4` is branch `1.10/image-cleanup` at
   `62e206058e09dee9ace00858b5f4a2b248dd67b1` with untracked `scripts/block_model_aabbs.json`; `Alpha-Piscium-6`
   retains tracked `GBufferSolid.frag.glsl` dirt; and `Alpha-Piscium-7` retains `_PdfExtract.java` and `tmp/`.
   These are treated as user-owned, remain unstaged and untouched, and are not validation targets for the synthetic
   T19I proof. The detached AP3/AP4 review copies remain at their recorded clean commits.
- `2026-08-13` initialized the authorized `I:\code\mcshader` fixture as a standalone Git repository. Commit
  `5923247281d4892c96984c27365f138bebcb82ba` compiled successfully under `night-gi-1-720p` with source snapshot
  `6b3edcedcccdeed2aa50033f4ad0f23198a0cef9c9fe45a4a6aa1e062824076e`, shader tree
  `4d744410299326865144cd60bec40fad18a2a2f3`, and the four-file source catalog. The first direct runtime load
  failed closed before any capture because the no-shadow fixture could not satisfy Iris's retained shadow render-list
  quiescence gate (`requested=65104`, `finalized=0`). The fixture was amended to commit
  `7c2fea6ba692c6bff14c2f2dcab9f4f9ce9ea2e5`, adding only no-op shadow entrypoints alongside the deterministic
  compute/final display path; its six-file snapshot is `342b3ebef4f4dee4b9c201754b9df69314af22d883214b5348da25b2689ba2e8`.
- Direct load job `ad665900-bea3-4974-b42b-c908d35c0114` used the original four-file commit
  `5923247281d4892c96984c27365f138bebcb82ba` (snapshot
  `6b3edcedcccdeed2aa50033f4ad0f23198a0cef9c9fe45a4a6aa1e062824076e`) under the exact preset, and failed closed at
  the prelude with `world_load_failed`: shadow render-list generation remained pending
  (`requested=65126`, `finalized=0`). Its restoration receipt preserved the original synthetic source/config/scene
  hashes and no artifact was published. The amended six-file commit has only been submitted to compile-validation:
  job `8257916b-b022-4c0a-b9c2-51dede7b4d8c` was cancelled before admission and
  `ff86d26e-fc59-4011-a8e1-e02c69925ab1` could not start because the shared runtime was held by an external
  `Alpha-Piscium-4b` recovery lease (`881d9500-3332-4d8e-ace4-79b6af20829f`,
  `768d176e-a54f-334b-a4b7-b34927ff053d`); status reported `can_accept_job=false`, `can_start_job=false`, and
  `SERVER_STATE_RECOVERING`. Minecraft was restarted only under the user's authorization; Codex was not restarted,
  and no other repository or protected T19H file was touched.
- `2026-08-13` direct polling of the newly built MCP executable rechecked the same shared-runtime lease six times
  over 30 seconds. The lease remained `881d9500-3332-4d8e-ace4-79b6af20829f` at
  `JOB_STAGE_RECOVERING` with `can_accept_job=false`, `can_start_job=false`, and artifact usage
  `3198825605/3221225472` bytes. No T19I compile or live comparison could be admitted; the external recovery was
  not cancelled or overridden, Minecraft and Codex were not restarted, and all protected repositories remained
  untouched.
- `2026-08-13` a direct `vibris_run_actions` probe exposed and fixed the durable action routing defect: the native
  durable runner had persisted `tool_name=vibris_run_actions` but dispatched every step to the recipe runner, which
  dereferenced a missing `recipe` key. The strict fix passes the persisted step's exact tool name through to
  `SynchronousJobRunner`; no alias or compatibility branch was added. Product commit `3b0be19` is
  `T19I route durable action sequences correctly`. Release targets `vibris-job-protocol-tests` and
  `vibris-synchronous-job-runner-tests` built, and CTest `JobProtocol|StrictV2Result|ScreenshotResult` passed 3/3.
  With the rebuilt direct executable `mcp/out/build/Release/vibris-mcp.exe` (15,378,432 bytes,
  SHA-256 `67659200785AAEE528881C4A01A2249D2A76BDBAFC3E25B1BE79AB19EEA14D24`), the same action reached the real
  Iris load path and failed closed at `world_load_failed`: request `fcbee31c-4c9d-4c65-ac18-09bd7e6f521b` from job
  `163e93e3-8248-4762-80e5-e913bc644c05` recorded amended source snapshot
  `342b3ebef4f4dee4b9c201754b9df69314af22d883214b5348da25b2689ba2e8`, with shadow render-list
  `requested=65231`, `finalized=0`; restoration remained `RECEIPT_STATUS_OK` and no artifact was published. The
  shared runtime was then held by external recovery job `7b77b5e1-aff7-40c9-9cc0-f00d4d9c9c15` / lease
  `80a17359-5773-3f58-aacb-687d81192989`, so no compile or comparison was admitted afterward.
- `2026-08-13` the authorized Minecraft-only restart cleared the stale lease and returned the direct v2 status to
  `SERVER_STATE_AVAILABLE` with `can_accept_job=true` and `can_start_job=true`; Codex was not restarted. A safe
  `restore_state=false/false` load of the existing user-owned `Alpha-Piscium-6` workspace completed as job
  `595e18fc-7dc2-4b34-85f1-faac3dcef30e`, preserving its dirty worktree and recording source snapshot
  `142649034efc14648293710c0538a3744d69e94c1681884463a9e15bc453a2cd`. Synthetic `compile_validate` job
  `863d1a79-4ed7-4436-9f26-6e780832b98f` then reached the real loader and paused only because the Core-owned safe
  snapshot was not yet available (`restore_failed`), with no compile diagnostic. After that snapshot, synthetic load
  job `cf2b35a5-a3ab-4884-9e34-94b37272c0e0` reached the synthetic link and failed closed at the existing shadow
  quiescence gate (`requested=19779`, `finalized=0`; restoration `RECEIPT_STATUS_OK`, source snapshot
  `342b3ebef4f4dee4b9c201754b9df69314af22d883214b5348da25b2689ba2e8`). At the same time, an independently
  authorized `Alpha-Piscium-5` compile job `cdf1d956-0863-41ed-a093-20451bb3d86e` entered recovery; the current
  shared lease is `b075274b-1583-458b-8225-67615a37a10e` / `943749f7-c9e2-366e-b651-4dab77152ecf`, workspace
  `24acfab9-fb0c-42c0-bce1-c1b6bd00117b`, so T19I comparison work is paused without cancelling or overriding the
  external recovery.
- `2026-08-13` bounded direct v2 status polls at `03:13:34`, `03:13:45`, `03:13:55`, `03:15:06`, and `03:15:16`
  all remained `SERVER_STATE_RECOVERING` with lease `b075274b-1583-458b-8225-67615a37a10e` at
  `JOB_STAGE_RECOVERING`, `can_start_job=false`, while Minecraft/world/scene readiness stayed true. The external
  `Alpha-Piscium-5` event log remained paused at `03:06:18`; no cancellation, override, deployment, or restart was
  performed, so the same external recovery blocker still prevents T19I live comparison.
- `2026-08-13` the complete entry audit found unrelated user-side shader drift since the prior receipt: the root
  `Alpha-Piscium` worktree is now on `2.0/dev` at `368e764329cfa4469d7eb3e1e7d32856505e0653`, and
  `Alpha-Piscium-8` is on `2.0/restir-optimize` at `7ca19441f490db73c813b6bb53550cd2603994d8` with its recorded
  dirty shader files. Those worktrees remain outside the synthetic target and were not touched. The direct status
  recheck still reports lease `b075274b-1583-458b-8225-67615a37a10e` / `943749f7-c9e2-366e-b651-4dab77152ecf` at
  `JOB_STAGE_RECOVERING`, so the second consecutive continuation cannot admit T19I's live proof and performs no
  cancellation, override, deployment, or restart.
- `2026-08-13` the third consecutive complete entry audit verified the same immutable repository/worktree and
  protected-state boundaries. Direct v2 status still reports the external `Alpha-Piscium-5` lease
  `b075274b-1583-458b-8225-67615a37a10e` / `943749f7-c9e2-366e-b651-4dab77152ecf` at
  `JOB_STAGE_RECOVERING`, with `can_accept_job=false`, `can_start_job=false`, and Minecraft/world/scene readiness
  true. T19I live proof remains inadmissible without an external recovery-state change; no cancellation, override,
  deployment, process restart, or protected-file mutation was performed. This is the third identical blocker audit;
  the Goal is marked blocked after this ledger-only checkpoint.
- `2026-08-13` the user resumed the blocked Goal for a fresh audit. The direct executable still reports the same
  `Alpha-Piscium-5` recovery lease and `SERVER_STATE_RECOVERING` through `06:40:02`; querying its durable job
  `cdf1d956-0863-41ed-a093-20451bb3d86e` confirms it remains `paused` with `ERROR_CODE_RESTORE_FAILED` and the
  explicit instruction to keep the runtime open, repair the link/bridge, and submit `recover_runtime`. The live
  shader link still targets synthetic pending source `6fd993d2-40fd-46eb-aa02-f7e25ca2e674`. Because that lease is
  owned by another authorized task, T19I does not cancel, override, recover, deploy, or restart it; this resumed
  continuation records the blocker and leaves the Goal active for a future recheck.
- `2026-08-13` the second resumed-audit poll at `06:44:43`, `06:44:53`, and `06:45:03` remained
  `SERVER_STATE_RECOVERING` with lease `b075274b-1583-458b-8225-67615a37a10e` /
  `943749f7-c9e2-366e-b651-4dab77152ecf`, `can_accept_job=false`, `can_start_job=false`, and empty queue/jobs.
  A direct strict-v2 `vibris_job` query still returned durable job `cdf1d956-0863-41ed-a093-20451bb3d86e` in
  `paused` / `restore_failed` with `ERROR_CODE_RESTORE_FAILED`, `resumable=false`, and the owner's manual recovery
  instruction not to release the lease or restart Minecraft. T19I therefore remains unable to admit live proof; no
  cancellation, override, recovery, deployment, process restart, or protected-file mutation was performed.
- `2026-08-13` the third resumed-audit entry check found the same repository/worktree/protected-state identities and
  direct status at `SERVER_STATE_RECOVERING`, lease `b075274b-1583-458b-8225-67615a37a10e` /
  `943749f7-c9e2-366e-b651-4dab77152ecf`, `can_accept_job=false`, and `can_start_job=false`. The external durable
  job directory's `state.json` and `events.jsonl` remain unchanged since `10:06:18`, ending at `paused` /
  `restore_failed` with the explicit instruction not to release the lease or restart Minecraft. T19I live proof is
  still inadmissible; no cancellation, override, recovery, deployment, process restart, or protected-file mutation
  was performed. This is the third identical resumed blocker audit; the Goal is marked blocked after this
  ledger-only checkpoint.

- `2026-08-13` the user-authorized synthetic fixture at `I:\code\mcshader` was amended with explicit
  `shadow.enabled=true` and committed as `a664ece1dbd49573f1a96765c34c13cce832828a` (six tracked shader files,
  source snapshot `e96e0f2cfb218ee2457e1815faa354b278f10ccfeb631427f056797ec894991f`, shader tree
  `54f763284a330206b0796e9bcabb7b8f79a77f09`, 1,015 bytes). Direct executable
  `I:\code\vibris\mcp\out\build\Release\vibris-mcp.exe` remained the only MCP entrypoint (15,378,432 bytes,
  SHA-256 `67659200785AAEE528881C4A01A2249D2A76BDBAFC3E25B1BE79AB19EEA14D24`); Codex configuration and the
  retained T19H files stayed untouched. Two consecutive direct `ab_compare` jobs under exact
  `night-gi-1-720p` with 120 warmup frames and unchanged `max_threshold_pixel_ratio=0.001` passed: job
  `6b2f369b-93c9-44dc-aee3-1fbe4a6188f8` captured distinct frames `27737/28068`, and job
  `de1f4714-e9b2-4bd4-90d1-4a5de0327ceb` captured distinct frames `30425/30765`. Both used the same clean
  commit on both sides, matching source snapshot/config/scene hashes
  (`e96e0f2cfb218ee2457e1815faa354b278f10ccfeb631427f056797ec894991f`,
  `e03b14a864edc17ba2f9890ad106ce6436d3f9e9a0b5c834117f052d5b99fa9a`,
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`), two ordered reset/load receipts,
  and restoration `RECEIPT_STATUS_OK`. Each comparison passed with zero max/mean/P95/RMSE error, SSIM 1,
  threshold-pixel ratio 0; both PNGs were 296,640 bytes with SHA-256
  `F54009E4E5A926CE771950C783985941AD2659C98FA54F06A038E2ECAD55F915`. The first artifact manifest is at
  `I:\code\mcshader\.vibris\artifact\5dfaba75-718b-30e0-922a-3e761e267cff\22943d65-7a51-3db8-9d81-fb9c3fdf01ef\manifest.json`
  (SHA-256 `6DFF0BA59B75D7360615E79744E6BA64F283AAB654FA0F1ECB63A977B736E2E9`), and the second is at
  `I:\code\mcshader\.vibris\artifact\6a4638d0-f64b-3c10-833c-76ba5791284b\e430fd51-16e5-3b83-91dd-8fde423ae044\manifest.json`
  (SHA-256 `7F56A9B9145DCDEF0D7FCBF507C145A977E226366E43D101AECE76EAE7E955EB`); diff metrics and heatmap
  artifacts were present and hash-verified in both manifests. A deliberately different temporary workspace output
  (pure-red `final.fsh`, reverted immediately) failed closed in job `313d970a-8f07-47c5-9b71-3a10faec2710`:
  result `completed_with_failures` / verdict `failed`, threshold-pixel ratio `1`, SSIM `0.014056324467699484`,
  and restoration `RECEIPT_STATUS_OK`; its two capture frames were `38745/39098` and its differing PNG hashes
  were `F54009E4E5A926CE771950C783985941AD2659C98FA54F06A038E2ECAD55F915` and
  `83FB625A7BAFEE0130FC5AFD45ABC4D83BF38599448718EA6B54C9FEB4A721E9`. Final direct v2 status is
  `SERVER_STATE_AVAILABLE` with `world_loaded=true`, `scene_applied=true`, empty queue/jobs, active source UUID
  `9911db8c-9a1e-4bb7-9b46-ff4f26d2565c`, and `can_accept_job=true` / `can_start_job=true`; the fixture worktree
  is clean except for runtime-untracked `.vibris/`.

### T19H — Make paired visual capture deterministic

Status: `DONE`

Dependencies: T19G, T19I

Repository: `I:\code\vibris`

Worktree: `I:\code\vibris`

Branch: `main`

Primary files:

- `I:\code\vibris\mcp\src\main\cpp\job_protocol.cpp`
- Focused MCP job-protocol, visual, and paired-benchmark tests
- `I:\code\vibris\docs\engineering-validation-v2-execution-ledger.md`

Scope:

- Make `ab_compare`, including the visual phase generated by `benchmark_ab`, enter each A/B warmup at the same
  shader-visible temporal phase. Use the existing strict-v2 `reset_temporal_state` action immediately after each source
  load and before the equal warmup/capture sequence, and retain both typed reset receipts.
- Preserve two distinct capture frames, exact source/config/scene provenance guards, immutable artifact manifests,
  unchanged fail-closed visual thresholds, and transactional restoration.
- Prove the correction live with the exact same clean authorized target on both sides under `night-gi-1-720p`, then
  leave the restored runtime available for T20. Prefer clean `Alpha-Piscium-8` branch `1.10/fsr3` commit
  `0c4112620b15dfd3b7684221714f58bda4fb6439`; if its unchanged shader race remains non-deterministic, use the fresh
  clean `I:\code\mcshader` commit containing only the user-authorized compute color-write test shader.

Non-scope:

- Do not weaken or calibrate thresholds, enable a debug output, inject shader settings to hide temporal output, reuse
  one capture or frame for both sides, mutate either supplied shader worktree, or add any compatibility path.
- Do not change the Iris temporal-reset implementation unless focused evidence after the planner correction proves the
  existing strict-v2 action is not honored equivalently; any newly exposed cross-repository defect must be split into a
  separate remediation task.

Acceptance:

- Focused protocol tests prove the exact order `load A -> reset -> warmup -> capture A -> load B -> reset -> warmup ->
  capture B -> compare`, and both resets return ordered successful typed receipts.
- A live same-commit authorized-target comparison uses two distinct frames and passes every unchanged threshold,
  including `max_threshold_pixel_ratio=0.001`, with matching source/config/scene hashes and successful restoration.
- Existing deliberately different visual fixtures still fail closed, and `benchmark_ab` consumes the corrected visual
  path without changing its performance measurements or verdict rules.
- Final live status is available with the entry source restored, an empty queue, and both accept/start true.

Verification:

- Focused release builds and native protocol/visual/paired-benchmark CTest scenarios.
- Direct live same-commit `night-gi-1-720p` comparison with artifact SHA-256 verification.
- Final `vibris_get_status` and protected Git-state audit.

Expected commit title: `T19H make paired visual capture deterministic`

Blockers:

- None. T19I is `DONE` under the user-authorized synthetic compute-shader validation scope; the supplied 1.10/1.9
  lines remain outside this task because their unchanged shader race is not an authorized mutation target.

Evidence:

- `2026-08-12` T20 resumed after T19G with Vibris `main` at
  `2e857aeb2b9ab8b5db02dc4e9d0053b35ce490e2` and Iris `1.21.11-shaderdev` at
  `5c5909726df7e39dcf35e1199d27aacd6ab64cf2`. All declared staging areas were empty and the exact recorded
  user-owned dirty/untracked state was preserved. The configured MCP is the 15,376,896-byte T19G executable with
  SHA-256 `5174826772A93B6169E81BDFDC2F85AD30235B14DA48A2EB224969BDA060FCEE`; the installed Mod is 28,166,451
  bytes with SHA-256 `68027918BD4F9A04D56937C765A111CA541BB5577AB8E074F247ADF033A93858`.
- A direct same-source control used clean AP4 commit `b793f75bc411b309142305ce062e17bc52b259c3` for both A and B,
  exact preset `night-gi-1-720p` with SHA-256
  `d3d37c2f3d751464214223d06ddd8b8924a54ac28be978608fd7eff5ea16dece`, empty config, and 120 warmup frames per
  side. Job/request `76ed19cd-58ec-4ea0-a833-dd50a43baacc` captured distinct frames 162836 and 162964 with matching
  source snapshot `434a67519dba9f2b0249a3e5766f496553151ccd1a3b1884d4730d0191500a5e`, settings
  `8313635afeae14ac96a2c3262474154f0942daf2a4315c20d878761b26d99944`, and scene
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`; every visual provenance guard passed.
- Despite identical inputs, the comparison failed solely on threshold-pixel ratio `0.08248046875 > 0.001`. Its SSIM
  `0.9996298450706469`, MAE `0.0014935544747399986`, RMSE `0.005267614461226407`, P95
  `0.00784313725490196`, and maximum error `0.24313725490196078` each passed their unchanged threshold. The current
  `ab_compare` plan contains load/wait/capture for each side but no temporal reset before either warmup, so the two
  shader-visible frame phases differ even though restoration later reports `temporal_state_reset=true`.
- Worktree-local artifacts are under
  `I:\code\mcshaders\Alpha-Piscium-4\.vibris\artifact\cd520538-bcd5-38ea-ba88-af07725e9fee\a5c08781-e3b7-35d3-8c3d-d5caab868b12`.
  The A/B PNG SHA-256 values are `30AB015A008F784F36408BED2CFFA7640EDFF4BCEE4039894AD26A04CC3FBB27` and
  `EBB8ECE4C5480771E98B320BDD2477FB98E5CB238CDDA265F9F0D51B0D61079B`; diff JSON, heatmap, result, and manifest
  SHA-256 values are `EB942755548A15E682F8697CE16722BF2B1CB9B64B3B39C2B6D4206295C4FD1D`,
  `4C7C44CBD3808E5FFB79EBE7C649C27F234AB819345D63BC48D9D3F0412644AD`,
  `80A47B50EE6DD18A0CC20478790D33DFAAE14C76B621A12A0B783B341D2439E1`, and
  `84E4E251F44DB355634E5005F29A26F57D1B369C8BB731E6822082127A33DD1A`.
- Restoration matched the original AP4 source/settings/scene hashes and source UUID
  `59b584ca-1698-45a1-9861-c67e3821c19a`. Final status is `SERVER_STATE_AVAILABLE`, the queue/jobs are empty,
  both accept/start are true, and workspace ID is `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc`. T20 stops before a formal
  benchmark rerun; no threshold, shader source, deployment, or process state was changed.
- `2026-08-12` the exact requested planner/typed-receipt correction passed focused Release tests, but two direct live
  same-clean-commit jobs still failed the unchanged pixel-ratio gate after both ordered resets. At that point T19H was
  not complete and its six task-owned source/test changes remained unstaged. T19I is inserted for the separately proven
  Core/Iris compound temporal-boundary defect; T19H resumes only after that cross-repository task is complete.
- `2026-08-13` T19H's retained six-file implementation passed the focused Release rebuild for
  `vibris-job-protocol-tests`, `vibris-paired-benchmark-tests`, `vibris-synchronous-job-runner-tests`, and `vibris-mcp`.
  The focused CTest filter `JobProtocolStrictV2Resume|StrictV2ResultShape|VisualNormalizationStrictV2|ScreenshotResultCompactStrictV2|PairedOrderStrategies|PairedAggregation|MeasuredNoiseFloorRejection|PairedMismatchGuards|PairedVisualGate|PairedVisualReceiptGuards|TypedGuardrailRegression|PairedDirectionReversal|PairedTemporalDrift|PairedCompileGate` passed 14/14. The direct executable was
  `I:\code\vibris\mcp\out\build\Release\vibris-mcp.exe`, 15,378,432 bytes, SHA-256
  `67659200785AAEE528881C4A01A2249D2A76BDBAFC3E25B1BE79AB19EEA14D24`; Codex MCP configuration and the installed
  deployment were not changed.
- The first rebuilt-executable live attempt `be84509a-bab7-401d-9f1c-065dd40b07f2` failed closed before either load
  because the retained Minecraft camera had drifted (`player yaw is -36.55001`); its restoration receipt was OK and
  the runtime remained available. A request-scoped non-restoring load `04b924ca-4996-4f1f-9aae-fd1a55863af2` then
  reapplied the exact `night-gi-1-720p` context and clean synthetic commit `a664ece1dbd49573f1a96765c34c13cce832828a`,
  with source snapshot `e96e0f2cfb218ee2457e1815faa354b278f10ccfeb631427f056797ec894991f`, config
  `e03b14a864edc17ba2f9890ad106ce6436d3f9e9a0b5c834117f052d5b99fa9a`, and scene
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`.
- The successful direct live `ab_compare` job `ac599b54-9c18-4b66-a844-e14b7ee87e15` used that same clean commit on
  both sides, exact `night-gi-1-720p`, 120 warmup frames, unchanged `max_threshold_pixel_ratio=0.001`, and distinct
  capture frames `57658`/`57943`. Its ordered receipts were load A, reset A, wait A, capture A, load B, reset B,
  wait B, capture B, compare; both typed reset receipts were successful. Source/config/scene hashes matched, every
  visual metric was zero with SSIM `1` and threshold-pixel ratio `0`, the visual guards passed, and restoration was
  `RECEIPT_STATUS_OK`. The A/B PNGs are 296,640 bytes each with SHA-256
  `F54009E4E5A926CE771950C783985941AD2659C98FA54F06A038E2ECAD55F915`.
- The returned primary manifest is
  `I:\code\mcshader\.vibris\artifact\c11d0155-ab37-3365-8d59-bd95994958b9\103c9b6c-207e-3375-96f1-b37b31a3568c\manifest.json`,
  2,651 bytes, SHA-256 `A485FB8B69BFE05716099281A9F01718BA179FD766433A59648D495706219CC3`; its diff metrics and heatmap
  artifacts were present. Final direct v2 status is `SERVER_STATE_AVAILABLE` with `minecraft_connected=true`,
  `world_loaded=true`, `scene_applied=true`, active source UUID `1f4a34d7-64d1-4351-a6fd-528a4dafd92d`, empty queue/jobs,
  and `can_accept_job=true` / `can_start_job=true`. The synthetic fixture remains clean except runtime-untracked
  `.vibris/`; all unrelated worktrees and `capture\a.spv` remain untouched.

### T20 — Run live two-worktree 720p acceptance

Status: `PENDING`

Dependencies: T19G, T19H

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

- Do not switch launchers. Retain prior AP4/AP3 receipts for the already-proven two-worktree queue, compile, settings,
  provenance, restoration, and pass-boundary portions; use the clean user-approved `Alpha-Piscium-8` `1.10/fsr3` target
  or the fresh user-authorized `I:\code\mcshader` compute color-write target for remaining temporal/visual proof. Do
  not require AP4's voxel allocator output to satisfy the same-source visual gate. New MCP validation must directly invoke its executable without changing Codex MCP deployment/
  configuration or restarting Codex. Task-owned Mod deployment and restart of the existing MultiMC `1.21.11-Iris`
  instance remain authorized for this session.

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

- None known beyond ordered dependency T19H. Direct new-MCP executable testing plus task-owned Mod deployment/MultiMC
  restart remain authorized; Codex MCP redeployment/configuration changes and Codex restart are not authorized.

Evidence:

- T19F resolved the final detached-checkout and maintained environment blocker with exact AP4/AP3 live receipts and
  a one-attempt filtered AP4 profile. This owner-receipt continuation ran no T20 benchmark, capture, mutation, or
  artifact job; T20 is promoted as the sole dependency-ready task for the next continuation.
- `2026-08-11` live acceptance attempt after T19C: entry gates verified Vibris `main` at
  `bca6169184b906b79ccec9352731f28a860e99dd`, Iris `1.21.11-shaderdev` at
  `3f3e458ea9fe904398bb28e4a8e05cb4c22e7afc`, every declared auxiliary worktree/head, empty staging areas, and the
  exact recorded protected/user-owned dirty state. The checker reported
  `Ledger valid: 27 task(s); next=T20; READY=1, PENDING=1, BLOCKED=0, DONE=25, SUPERSEDED=0.` Source/deployed MCP
  hashes matched at `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`; source/installed Mod hashes matched at
  `6950BBE417E098E8657F6A776720ABE69C7E67FD5B5BAAFA1F006551444DE9B8`.
- Fresh main-menu status returned `SERVER_STATE_AVAILABLE`, `minecraft_connected=true`, `world_loaded=false`,
  `scene_applied=false`, and both accept/start true. Baseline AP4 job `73ed671d-13f1-44db-bd84-9c7f3d871284`
  loaded `night-gi-1-720p` and established source/settings/scene SHA-256 values
  `d3ca7a48b7589b9b185ab3c9357364f3817776de138c163851a170d338153e65`,
  `8313635afeae14ac96a2c3262474154f0942daf2a4315c20d878761b26d99944`, and
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`.
- Shared-runtime queueing exposed AP4 lease `ddad7b5b-7866-38a5-be6a-92afaa53b3f2` for job
  `a78decbc-6c69-4e45-bb71-be42ce24f373` while AP3 job `8f583eef-1883-47c2-a327-1484e55d7a56` occupied queue
  position 1. Both completed in order; AP3 recorded `queue_ms=49826`, and both restored the three AP4 baseline hashes.
- AP4 compile-validation job `25482275-6656-4c48-b580-319ba5361ebc` passed its complete intended catalog with no
  diagnostics. AP3 job `cf1f8111-e90d-4417-8700-0c7d577dd094` passed 182/182 programs for clean revision
  `9325c7a091647a3d8243720d06802bdc2640292e` and source SHA-256
  `3a68d7ba4c2e30b139affee23fd3b42e5f4e4cdd57d379df1c53afd568e4b61b`; final active source returned to AP4.
- Transaction `4bfac181-9790-45da-b520-4d358445c289` proved `SETTING_DEBUG_WHITE_WORLD=false` with origin
  `SHADER_SETTING_ORIGIN_PRESERVED_CURRENT`, then `true` with origin `SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE`, and
  restored the exact AP4 source/settings/scene hashes. Screenshot job `86b51a39-ebb9-4669-8207-1ccc7c31c955`
  produced an upright 1280x720 image at the worktree-local artifact path, 1,813,283 bytes with SHA-256
  `93C57BAEE9923C0E70B86F3426AD853C5E6BC723A5B35174FD21E79BA20945B9`; its 1,535-byte manifest has SHA-256
  `380ACCFB0CA0AB633EA2B82FD5DD244138CD79112343CDC315814C5E9BB3E4C4` and restoration matched all three axes.
- Grouped exact-boundary job `01424583-1c8b-4c2f-9abf-f1f89e38d31c` captured `colortex0.main` and
  `colortex0.alt` after `final/final` in frame 8473. Their upright current and dark alternate PNGs differ at SHA-256
  `6098C0B5211D8B395DD157947E0FF2389473DAC17E4AB2EE942B765AAED09AED` and
  `2D726ADAF5DC3FA1002583CC7E34B893653EEBBE742252F9452A9B484B52714B`. The same frame's 16,384-byte
  `iris_ssbo_0` captures after `composite/composite14` and `composite/composite15` differ at SHA-256
  `9B020A0288853532827D9C799CE58802BEB589F606844119D6B03CE7636FC218` and
  `120031CEB7288F460B3D71E26D2989220EA99CA9E81E2B2FB6BFE971386676AA`, proving the RC clear/touch boundary; the
  result and manifest hashes are `8476B6E322A67C91D739DCE1A59E816EC89CD3DB8D032481EF3AC9422EA03BCB` and
  `C07EEE84D7638C863F10A1D237EC1D9560D8837E0298484EB6F3DDFA0811EE49`. Restoration again matched all three axes.
- The subsequent live metric failure is recorded under T19D. T20 stops before benchmark completion and remains
  `BLOCKED`; no shader source was modified or staged.

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
- `2026-08-11` resumed-scope audit: Vibris entered at
  `ba416b5db117ba5e985387b097e1d84c3fc21ef7`, Iris remained at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree and staging area remained clean, and
  only the recorded protected untracked state remained. The user-authorized side-by-side deployment is now active:
  `config.toml` selects the 15,350,272-byte MCP with SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`, direct stdio inspection returned exactly
  eight schema-2 tools with concrete input/output schemas, and Java PID 8612 is the new listener on
  `127.0.0.1:50051`. The installed 28,145,118-byte Iris mod has SHA-256
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`, zero nested protocol-v1 entries, and 536
  protocol-v2 entries.
- The user explicitly supplied `I:\code\mcshaders\Alpha-Piscium-4` and
  `I:\code\mcshaders\Alpha-Piscium-3` for T20. Both are detached worktrees with empty staging areas at
  `b793f75bc411b309142305ce062e17bc52b259c3` and `9325c7a091647a3d8243720d06802bdc2640292e` respectively.
  Alpha-Piscium-3 is clean. Alpha-Piscium-4 has pre-existing user-owned tracked changes in `scripts/shadesmith.jar`,
  `scripts/voxel-trace-contract.main.kts`, `shaders/techniques/voxel/BlockModels.glsl`,
  `shaders/textures/block_model_quads.bin`, and `shaders/textures/pbr_lut_2.bin`, plus untracked
  `scripts/block_model_aabbs.json`; T20 must preserve them and may only observe them as part of that supplied source.
- Read-only `vibris_get_status` calls resolved independent workspace IDs
  `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc` and `90485cf5-12a0-45b9-bb89-7141a9b7ee1e`, but both returned
  `SERVER_STATE_FAILED`, `core_online=false`, and
  `UNSUPPORTED_VERSION: server.json schema_version must be 2`. The exact old config is 366 bytes with SHA-256
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`; it was not read, migrated, overwritten,
  moved, or deleted. No live job/action, artifact capture, source mutation, or process restart occurred. This is the
  first blocked Goal turn after resumption; T20 remains `BLOCKED`, T99 remains `PENDING`, and the Goal remains active.
- `2026-08-11` resumed blocker audit 2: Vibris remained on `main` at
  `2b8e9e5589568af05a5260479135ecb69dd0d96c`, Iris remained on `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, all declared auxiliary worktrees, staging areas, and protected state
  remained exact, and the ledger checker still reported `READY=0, PENDING=1, BLOCKED=1, DONE=22`. The supplied
  Alpha-Piscium-4 and Alpha-Piscium-3 worktrees remained at their recorded detached HEADs with the exact recorded
  user-owned dirty/clean states and empty staging areas. The same Java PID 8612 continued listening from its original
  start time, while the deployed MCP and mod hashes remained
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26` and
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`.
- The old `server.json` remained byte-for-byte unchanged at 366 bytes with SHA-256
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`. Fresh read-only status calls for both
  recorded workspace IDs again returned schema 2 envelopes with `SERVER_STATE_FAILED`, `can_accept_job=false`,
  `can_start_job=false`, `core_online=false`, and the identical `UNSUPPORTED_VERSION` detail. No configuration
  content was read, and no migration, overwrite, move, deletion, live job/action, artifact capture, source mutation,
  deployment, or process restart occurred. This is the second consecutive blocked Goal turn after resumption; T20
  remains `BLOCKED`, T99 remains `PENDING`, and the Goal remains active.
- `2026-08-11` resumed blocker audit 3: Vibris entered on `main` at
  `3a5b379a34225016b82515dcfc63e3e67ca38cac`, Iris remained on `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree remained at its recorded clean HEAD,
  all staging areas were empty, and the ledger checker again reported `READY=0, PENDING=1, BLOCKED=1, DONE=22`.
  Alpha-Piscium-4 and Alpha-Piscium-3 remained at detached HEADs
  `b793f75bc411b309142305ce062e17bc52b259c3` and `9325c7a091647a3d8243720d06802bdc2640292e` with their exact recorded
  user-owned dirty/clean states and empty staging areas.
- The same Java PID 8612 continued listening on `127.0.0.1:50051` from
  `2026-08-12T01:39:51.9743170Z`. The deployed MCP and installed mod remained byte-for-byte unchanged with SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26` and
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`. The old `server.json` also remained unchanged
  at 366 bytes, last-written `2026-08-04T02:50:02.7107489Z`, with SHA-256
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`.
- Fresh read-only status calls for workspace IDs `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc` and
  `90485cf5-12a0-45b9-bb89-7141a9b7ee1e` again returned schema-2 envelopes with `SERVER_STATE_FAILED`,
  `can_accept_job=false`, `can_start_job=false`, `core_online=false`, and the identical
  `UNSUPPORTED_VERSION: server.json schema_version must be 2` detail. No configuration content was parsed or
  consumed, and no migration, overwrite, move, deletion, live job/action, artifact capture, source mutation,
  deployment, or process restart occurred. This is the third consecutive blocked Goal turn after resumption; after
  this ledger-only atomic checkpoint the Goal is marked `blocked`, while T20 remains `BLOCKED` and T99 remains
  `PENDING`.
- `2026-08-11` config-cutover resolution: under explicit user authorization, Minecraft was stopped, the old
  schema-v1 file was moved intact to `server.schema-v1-20260812T021504Z.json`, and Minecraft was restarted through
  the existing MultiMC instance. The archive remains 366 bytes with SHA-256
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`; the active configuration is schema 2 and
  has SHA-256 `6607CF94249CE8335CEA7FEAB1DE98A1A8ACA5F96256CECF4003DC3B86F94EFA`.
- The main shader worktree now lists `night-gi-1-720p` version 2 with the exact 1280x720 context and preset SHA-256
  `d3d37c2f3d751464214223d06ddd8b8924a54ac28be978608fd7eff5ea16dece`. Transactional screenshot job/request
  `c784be59-5c2f-4854-a809-9855325af585` completed and published a 2,209,250-byte PNG under the worktree-local
  `.vibris\artifact` tree with SHA-256
  `16A20E15F37411BBDF78E2FBE5E238FC3DF051674881E03B1A0D9E90EA3E50E5`; final live status is
  `SERVER_STATE_AVAILABLE`, `core_online=true`, `minecraft_connected=true`, `world_loaded=true`,
  `can_accept_job=true`, and `can_start_job=true`.
- That recovery also exposed the four T19A defects before two-worktree acceptance could proceed: the generated
  shaderpack root omits the fixed pack component, unavailable unary RPCs report `UNIMPLEMENTED`, the public
  `load_shader` schema disagrees with its argument policy, and fresh screenshot planning requires a resource catalog
  before its generated load prelude. T20 therefore waits on T19A; no T20 queueing, compile, benchmark, after-pass, or
  restoration gate is claimed complete.
- A later successful screenshot request exposed T19B: the synchronous response returned a 192,710-character raw
  result with 692 expanded setting entries and five paths under the Minecraft backing artifact root instead of the
  request worktree `.vibris\artifact` link. T20 now also waits for compact result projection and recursive path
  localization; the successful screenshot itself does not satisfy any T20 artifact or response-shape gate.
- `2026-08-11` post-T19B blocker audit: entry gates verified Vibris `main` at
  `4a5c60b29562e682b768b6801abe6bbb35d0ac23`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, all declared auxiliary worktree HEADs, empty staging areas, and the exact
  protected dirty state. Alpha-Piscium-6 additionally retains the pre-existing untracked `tmp/` first observed after
  T19B; it was not read or modified. The checker reported
  `Ledger valid: 26 task(s); next=T20; READY=1, PENDING=1, BLOCKED=0, DONE=24, SUPERSEDED=0.`
- `C:\Users\Luna5ama\.codex\config.toml` still selects
  `I:\code\vibris\build\delivery-20260811-strict-v2\vibris-mcp.exe`, 15,350,272 bytes with SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`; the current post-T19B release executable is
  15,368,192 bytes with SHA-256 `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`. The selected MCP still exposes the strict-v2
  eight-tool surface but cannot prove T19B's compact response or recursively localized artifact paths.
- The installed Mod remains 28,145,118 bytes with SHA-256
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`; its `2026-08-12T00:45:58.4344196Z` build time
  predates T19A commit `d0bcfbf355e229abdc24e4a19d2f13bf5ef9f979` at `2026-08-12T03:01:32Z`, whose Core changes are required for
  correct fixed-pack bootstrap, unavailable unary behavior, and pre-load resource planning. The active schema-2
  configuration is 486 bytes with SHA-256 `6607CF94249CE8335CEA7FEAB1DE98A1A8ACA5F96256CECF4003DC3B86F94EFA`;
  the archived schema-v1 file remains intact and unchanged.
- Java PID 52460 still listens on `127.0.0.1:50051`, but read-only status calls for Alpha-Piscium-4 workspace
  `e5e1f8a1-2532-4972-9bad-2dcf6a0c72cc` and Alpha-Piscium-3 workspace
  `90485cf5-12a0-45b9-bb89-7141a9b7ee1e` both returned `SERVER_STATE_FAILED`, `core_online=true`,
  `minecraft_connected=false`, `world_loaded=false`, `can_start_job=false`, and `RUNTIME_PHASE_DISCONNECTED`.
  No live job, screenshot, compile, benchmark, after-pass capture, artifact write, deployment, restart, or protected
  source mutation occurred. This is the first blocked Goal turn after T19B; T20 is `BLOCKED`, T99 remains `PENDING`,
  and the Goal remains active.
- `2026-08-11` post-T19B blocker audit 2: Vibris remained on `main` at
  `3b68f7ca01448635ca40bf3cc0e25b1945ce2658`, Iris remained on `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree remained at its recorded HEAD, all
  staging areas were empty, and the checker reported
  `Ledger valid: 26 task(s); next=none; READY=0, PENDING=1, BLOCKED=1, DONE=24, SUPERSEDED=0.` The exact recorded
  protected state remains untouched. Alpha-Piscium-6 now additionally has concurrent user-owned tracked changes in
  `shaders/pass/composite/GIReSTIRPairedSpatialShade.comp.glsl`,
  `shaders/pass/composite/GIReSTIRTemporalReuse.comp.glsl`, `shaders/techniques/gi/ResampleMaterial.glsl`,
  `shaders/techniques/gi/Reservoir.glsl`, and `shaders/util/SplitSumSpecular.glsl`, plus untracked `tmp/`; none was
  read, modified, or staged.
- `config.toml` still selects the 15,350,272-byte MCP with SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`, while the post-T19B release executable
  remains 15,368,192 bytes with SHA-256 `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`.
  The installed Mod and local Iris build output remain byte-for-byte identical at 28,145,118 bytes with SHA-256
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`, still predating T19A. The active schema-2
  configuration and archived schema-v1 file remain unchanged at SHA-256
  `6607CF94249CE8335CEA7FEAB1DE98A1A8ACA5F96256CECF4003DC3B86F94EFA` and
  `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`.
- The same Java PID 52460 continues listening on `127.0.0.1:50051` from
  `2026-08-12T02:21:23.6188834Z`. Fresh read-only status calls for both recorded workspace IDs again returned schema-2
  envelopes with `SERVER_STATE_FAILED`, `core_online=true`, `minecraft_connected=false`, `world_loaded=false`,
  `can_start_job=false`, and `RUNTIME_PHASE_DISCONNECTED`. No job, artifact write, deployment, restart, configuration
  mutation, or source mutation occurred. This is the second consecutive blocked Goal turn after T19B; T20 remains
  `BLOCKED`, T99 remains `PENDING`, and the Goal remains active.
- `2026-08-11` post-T19B blocker audit 3: Vibris entered on `main` at
  `82ae5d0d26404001e74497fb6795b4f97a5a034c`, Iris remained on `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree remained at its recorded HEAD, all
  staging areas were empty, and the checker again reported
  `Ledger valid: 26 task(s); next=none; READY=0, PENDING=1, BLOCKED=1, DONE=24, SUPERSEDED=0.` The recorded
  Alpha-Piscium-4, Alpha-Piscium-6, Alpha-Piscium-7, runtime, and untracked user-owned state remained exact and was
  not read, modified, or staged.
- `config.toml` still selects the 15,350,272-byte MCP with SHA-256
  `2BA67FAB3290C0222A4F5CA8FB62D8DF59DBD8C29808BEFEF460033C1922CC26`; the post-T19B release executable remains
  15,368,192 bytes with SHA-256 `8CD4AC8B9E93E6E75FD2F294340673A77C518AD833819300C0EB7D54F6F615C6`.
  The installed Mod remains the 28,145,118-byte pre-T19A build with SHA-256
  `C0E856A3F169E57DBC23283383A77059E82030A847B799EC468A890F02A1E02F`. The active schema-2 configuration and archived
  schema-v1 file remain unchanged at SHA-256 `6607CF94249CE8335CEA7FEAB1DE98A1A8ACA5F96256CECF4003DC3B86F94EFA`
  and `9CF549579FF4B8E4892504DB25D89A4C6C5F85673BFCDF0878F9A8739065BA70`.
- The same Java PID 52460 still listens from `2026-08-12T02:21:23.6188834Z`. Fresh read-only status calls for both
  recorded workspace IDs again returned `SERVER_STATE_FAILED`, `core_online=true`, `minecraft_connected=false`,
  `world_loaded=false`, `can_start_job=false`, and `RUNTIME_PHASE_DISCONNECTED`. No job, artifact write, deployment,
  restart, configuration mutation, or source mutation occurred. This is the third consecutive blocked Goal turn after
  T19B; after this ledger-only atomic checkpoint the Goal is marked `blocked`, while T20 remains `BLOCKED` and T99
  remains `PENDING`.
- `2026-08-11` deployment-permission resolution: entry gates verified Vibris `main` at
  `3f8899a334fbb8c7b45db923943252a5731c0f14`, Iris `1.21.11-shaderdev` at
  `38a7d2eaf88939983e0e01f731ccd4c627fbf6a9`, every declared auxiliary worktree at its recorded HEAD, every staging
  area empty, and all recorded protected and user-owned dirty state unchanged. Before this transition the checker
  reported `Ledger valid: 26 task(s); next=none; READY=0, PENDING=1, BLOCKED=1, DONE=24, SUPERSEDED=0.`
- The user explicitly authorized repeated MCP/Mod deployment and restart of the existing MultiMC `1.21.11-Iris`
  instance for the remainder of this session. Codex application restart remains user-owned. T20 is restored as the
  sole `READY` task against the already supplied Alpha-Piscium-4 and Alpha-Piscium-3 worktrees. No deployment, build,
  restart, runtime request, artifact write, or source mutation occurs in this control-plane continuation; execution
  resumes next continuation so this permission change remains an atomic ledger-only commit.
- `2026-08-12` current T20 attempt used the verified current MCP directly against the shared runtime. AP4 compile job
  `f99a5b92-f405-4ee1-8e08-2daec1247809` and AP3 compile job
  `06f09b4b-1759-446f-a28c-c7baafc08620` each passed all 182 programs with exact detached HEADs and no diagnostics.
  Concurrent AP4/AP3 profiles `29aee41b-bec5-489d-a2ec-152bca4f0ca2` and
  `cb27a4eb-d071-45a6-afbe-b3dfc5c585d7` completed through one fair shared queue; the latter recorded 12,375 ms of
  queue time, and both restored the AP4 baseline.
- Effective-settings transaction `52b8cdea-dd08-4196-9357-1f482b1c8e61` proved preserved
  `SETTING_DEBUG_WHITE_WORLD=false`, explicit override `true`, exact origins, and restoration to source/settings/scene
  SHA-256 values `d3ca7a48b7589b9b185ab3c9357364f3817776de138c163851a170d338153e65`,
  `8313635afeae14ac96a2c3262474154f0942daf2a4315c20d878761b26d99944`, and
  `6541ce12e1e9e7ecbea47971c0a7eec90fde0d50407b547377791b8c099fa674`. Screenshot job
  `541a76eb-aa6e-446c-851f-6f012b012243` produced a visually verified upright 1280x720 PNG at the worktree-local artifact
  link, 2,250,111 bytes with SHA-256 `6613FD3BADE684FFB0DADDA92B05AE349E8C10C386E0998517D121922E2CFDCA`.
- Formal ABBA job `76e06187-da08-4e96-9df9-d3edc6e1195d` used AP3 commit
  `9325c7a091647a3d8243720d06802bdc2640292e` against AP4 workspace, two 60-frame comparison rounds, two same-source
  controls, target `composite_total`, sibling `begin3_a_compute`, sentinel `final_total`, and unchanged strict visual
  thresholds. All 17 steps were checkpointed, but final publication failed on an unnormalized protobuf-JSON integer;
  every nested profile also lacked explicit `preset_id` and was marked provenance-incomplete. The visual receipt
  independently failed its unchanged thresholds with SSIM `0.8143305138814317`. T19G is inserted before any rerun;
  no fresh after-pass capture, threshold weakening, result fabrication, or T20 acceptance claim occurred.
- `2026-08-12` post-T19G same-source control `76ed19cd-58ec-4ea0-a833-dd50a43baacc` proved the current visual
  plan cannot satisfy its unchanged threshold even when both sides resolve to the exact clean AP4 commit, source,
  settings, and scene. The plan omitted a temporal reset before each equal warmup; T19H is inserted to correct and
  prove that boundary before T20 resumes. Runtime restoration passed and no shader source was modified.

### T99 — Final integrated audit

Status: `PENDING`

Dependencies: T01, T02, T02A, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T12A, T13, T14, T15, T16, T17, T18, T19, T19A, T19B, T19C, T19D, T19E, T19F, T19G, T19I, T19H, T20

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
    -> T19 -> T19A -> T19B -> T19C -> T19D -> T19E -> T19F -> T19G -> T19I -> T19H -> T20 -> T99
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
- [x] Synchronous capture recipes return compact task-specific payloads while durable detailed evidence remains
  available, and every user-visible artifact path is localized beneath the request worktree `.vibris\artifact` link
  with no internal storage-path leakage.
- [x] `dump_texture_after_pass` and `dump_buffer_after_pass` capture exact named pass boundaries with correct flip, visibility, bytes, artifacts, and GL-state restoration.
- [x] Full Vibris native/Gradle and Iris build validation passes.
- [x] A fresh schema-2 runtime has the correct fixed-pack root, truthful unavailable unary failures, a usable typed
  first-load action, and post-load screenshot resource planning without a sacrificial job.
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
- `2026-08-11 - T19A inserted - live clean-cutover recovery exposed an invalid default shaderpack root, unavailable unary UNIMPLEMENTED responses, a load_shader field mismatch, and pre-load screenshot resource planning; inserted a no-compatibility strict-v2 bootstrap remediation before T20 - Control-plane commit title: roadmap insert T19A strict v2 live bootstrap remediation`
- `2026-08-11 - T19A - fixed the strict schema-2 shaderpack root, implemented every unavailable unary RPC, aligned load_shader on source_id/config_id only, and moved capture planning/reservation after the single generated load prelude; Core 102/102 and focused native 2/2 passed with no compatibility path - Commit title: T19A repair strict v2 live bootstrap`
- `2026-08-11 - T19B inserted - a live load_and_screenshot response returned a 192710-character raw JobResult with 692 expanded setting entries and five Minecraft-internal artifact paths; inserted compact recipe projection and recursive worktree-path localization remediation before T20 - Control-plane commit title: roadmap insert T19B compact screenshot result remediation`
- `2026-08-11 - T19B - projected synchronous screenshots to one bounded screenshot/manifest response, recursively localized all strict-v2 artifact paths after atomic validation, and passed focused release build plus CTest 6/6 - Commit title: T19B compact capture results and localize artifact paths`
- `2026-08-11 - T19C - Iris reports the initialized main-menu runtime as connected independently of world/scene state; external commit 3f3e458ea9fe904398bb28e4a8e05cb4c22e7afc, focused Core status tests and Iris build passed, deployed Mod/MCP hashes matched, and live main-menu status was available with can_start_job=true - Owner receipt commit title: T19C record main menu readiness receipt`
- `2026-08-11 - T20 blocked - the live instance embeds 335 v1 and zero v2 protocol entries, the configured MCP publishes only the old five-tool surface, and no two shader worktrees were supplied; no compatibility path, deployment, or restart was attempted - Control-plane commit title: roadmap block T20 pending matching live scope`
- `2026-08-11 - T20 blocker audit 2 - reverified the unchanged pre-v2 runtime/MCP and missing two-worktree scope; this is the second consecutive blocked Goal turn, so the Goal remains active - Control-plane commit title: roadmap recheck T20 live scope blocker`
- `2026-08-11 - T20 blocker audit 3 - reverified the unchanged pre-v2 runtime/MCP and missing two-worktree scope for the third consecutive blocked Goal turn; the Goal is marked blocked after the ledger-only atomic checkpoint - Control-plane commit title: roadmap confirm T20 blocked awaiting live scope`
- `2026-08-11 - T20 resumed scope audit - verified the deployed strict-v2 eight-tool MCP/mod and the two user-supplied shader worktrees, then found the runtime correctly rejecting the untouched schema-v1 server.json; T20 remains blocked pending user archival and restart - Control-plane commit title: roadmap record T20 v2 config blocker`
- `2026-08-11 - T20 resumed blocker audit 2 - reverified the unchanged schema-v1 server.json rejection, strict-v2 deployment, two workspace identities, and protected worktree state; this is the second blocked Goal turn after resumption, so the Goal remains active - Control-plane commit title: roadmap recheck T20 v2 config blocker`
- `2026-08-11 - T20 resumed blocker audit 3 - reverified the unchanged schema-v1 server.json rejection for the third consecutive blocked Goal turn after resumption; the Goal is marked blocked after the ledger-only atomic checkpoint - Control-plane commit title: roadmap confirm T20 blocked on v2 config cutover`
- `2026-08-11 - T20 post-T19B blocker audit - current Mod predates T19A, configured MCP predates T19B, and the runtime bridge is disconnected; no job, deployment, or restart was attempted - Control-plane commit title: roadmap block T20 pending current live deployment`
- `2026-08-11 - T20 post-T19B blocker audit 2 - reverified the unchanged stale MCP/Mod deployment and disconnected runtime for the second consecutive Goal turn; new Alpha-Piscium-6 dirt was protected as user-owned - Control-plane commit title: roadmap recheck T20 current live deployment blocker`
- `2026-08-11 - T20 post-T19B blocker audit 3 - reverified the unchanged stale MCP/Mod deployment and disconnected runtime for the third consecutive Goal turn; the Goal is marked blocked after the ledger-only checkpoint - Control-plane commit title: roadmap confirm T20 blocked on current live deployment`
- `2026-08-11 - T20 unblocked after T19B - user authorized repeated current MCP/Mod deployments and MultiMC 1.21.11-Iris restarts for this session; Codex restart remains user-owned - Control-plane commit title: roadmap unblock T20 for authorized live deployment`
- `2026-08-11 - T19D inserted - live get_gpu_metrics completed OK but Core discarded the runtime JSON and emitted EmptyReceipt, preventing profile and paired-benchmark statistics; inserted a no-compatibility typed metric-receipt remediation before T20 - Control-plane commit title: roadmap insert T19D typed GPU metric remediation`
- `2026-08-11 - T19D - preserved canonical live GPU timing JSON as filtered strict-v2 typed receipts with real samples and explicit empty/malformed failures; Capture 26/26, Core 105/105, native 13/13, Iris build, deployed hashes, and a one-attempt filtered live AP4 profile passed; inserted T19E for the separately exposed detached provenance blocker - Commit title: T19D return typed GPU metric receipts`
- `2026-08-11 - T19E/T19F split - contract auditing proved truthful Minecraft/GPU environment identity requires a direct Iris host implementation while detached VCS state belongs to Vibris protocol/Core/native code; split the work so Vibris T19E lands the strict contract before external Iris T19F implements and proves it live - Control-plane commit title: roadmap split T19E cross repository provenance`
- `2026-08-11 - T19E - required exact host-supplied runtime environment identity, added canonical attached/detached checkout provenance through source checkpoints and results, rejected branch-only and malformed detached shapes, and passed API 9/9, Core 108/108, test-runtime compilation, native 30/30, and zero-match no-fallback scans - Commit title: T19E define strict detached provenance contract`
- `2026-08-12 - T19F - Iris external commit 5c5909726df7e39dcf35e1199d27aacd6ab64cf2 supplied exact client-thread runtime identity; bridge tests passed 17/17, deployed MCP/Mod hashes matched, AP3/AP4 retained exact detached HEADs, and AP4 night-gi-1-720p profile 0f8d20d6-4a98-4472-8b91-9006aecff159 passed one attempt with typed samples and restoration - Owner receipt commit title: T19F record live detached provenance receipt`
- `2026-08-12 - T19G inserted - T20 checkpointed all 17 paired-benchmark steps but final publication hit a protobuf-JSON integer type exception; nested requests omitted preset_id and made every profile provenance-incomplete, while the fully checkpointed non-retryable state could not resume finalization without replay - Control-plane commit title: roadmap insert T19G live benchmark remediation`
- `2026-08-12 - T19G - preserved exact nested preset identity, normalized strict-v2 protobuf integers at one native boundary, added safe receipt-only finalization resume, passed focused CTest 20/20, and published a fail-closed 17-receipt live AP3/AP4 benchmark with full restoration - Commit title: T19G repair live paired benchmark finalization`
- `2026-08-12 - T19H inserted - an exact same-clean-commit AP4 comparison under night-gi-1-720p matched source, settings, scene, and all visual guards but failed only threshold-pixel ratio because ab_compare omitted temporal reset before each equal warmup; inserted a no-threshold-change deterministic-phase remediation before T20 - Control-plane commit title: roadmap insert T19H visual determinism remediation`
- `2026-08-12 - T19I inserted - T19H's ordered planner resets passed focused tests and both typed receipts, but two direct same-clean-commit live jobs still failed the unchanged pixel-ratio gate; Core leaves load/reset/wait/capture as non-atomic handoffs and Iris resumes shader time from wall-clock deltas, so a no-compatibility cross-repository compound temporal-phase remediation is inserted before T19H - Control-plane commit title: roadmap insert T19I frame atomic temporal remediation`
- `2026-08-12 - T19I blocker audit 2 - reverified the unchanged AP4 cross-workgroup allocation-ID nondeterminism, exact repository/worktree identities, and protected state for the second consecutive blocked Goal turn; no READY task exists and the Goal remains active - Control-plane commit title: roadmap recheck T19I allocator blocker`
- `2026-08-12 - T19I blocker audit 3 - reverified the unchanged AP4 cross-workgroup allocation-ID nondeterminism, exact repository/worktree identities, and protected state for the third consecutive blocked Goal turn; after the ledger-only checkpoint the Goal is marked blocked - Control-plane commit title: roadmap confirm T19I blocked on shader allocator`
- `2026-08-12 - T19I scope unblocked - user authorized the clean 1.10/1.9 line for deterministic validation and made AP4 voxel-specific divergence non-gating; existing clean Alpha-Piscium-8 1.10/fsr3 at 0c4112620b15dfd3b7684221714f58bda4fb6439 has no voxel paths or allocator, so T19I is READY and dependents return to PENDING - Control-plane commit title: roadmap unblock T19I with 1.10 validation target`
- `2026-08-12 - T19I Vibris hardening - deterministic cleanup is fail-closed, recovery waits for unresolved cleanup, and queued ordinary jobs cannot enter a recovering runtime; API/Core/test-runtime 216/216 and offline build passed - Commit title: T19I fail closed deterministic cleanup and recovery`
- `2026-08-13 - T19I blocked - Iris product commit a58f107f3a7e77d8447ba04998e9ae49f39e12a0 and repeated direct Alpha-Piscium-8 same-source proofs passed all focused/runtime/provenance/restoration checks but failed the unchanged threshold-pixel-ratio gate; AP8 EnvProbe scatter has an in-dispatch imageStore race that cannot be fixed within the no-shader-change/no-threshold-change/no-compatibility scope - Control-plane commit title: roadmap block T19I on AP8 shader scatter nondeterminism`
- `2026-08-13 - T19I blocker audit 2 - reverified every declared repository/worktree/protected boundary and found the authorized clean 1.9 line has the same EnvProbe scatter race as AP8; no alternate target or in-scope runtime fix exists, and no later task was started - Control-plane commit title: roadmap recheck T19I authorized branches remain blocked`
- `2026-08-13 - T19I blocker audit 3 - reverified the unchanged AP8 EnvProbe scatter source, all repository/worktree/protected boundaries, and an available empty-queue runtime; the same blocker persists for the third consecutive Goal turn, so the Goal is marked blocked after this ledger-only checkpoint - Control-plane commit title: roadmap confirm T19I blocked on authorized shader targets`
- `2026-08-13 - T19I scope unblocked - user authorized a fresh clean Git validation worktree under I:\code\mcshader containing a minimal compute shader that writes deterministic colors directly; T19I is READY again, while existing Alpha-Piscium worktrees, thresholds, compatibility boundaries, and protected state remain unchanged - Control-plane commit title: roadmap unblock T19I for synthetic compute shader`
- `2026-08-13 - T19I - completed the frame-atomic deterministic temporal phase and recorded two passing same-clean-commit synthetic comparisons plus a fail-closed different-output guard; artifacts, source/config/scene provenance, distinct frames, restoration, and final available status were verified - Owner receipt commit title: T19I record deterministic temporal phase proof`
- `2026-08-13 - T19H - added reset-after-load ordering and typed temporal-reset visual guards, passed focused native 14/14 plus Release rebuild, recovered one transient retained-camera drift with a scoped load, and recorded passing live same-clean-commit synthetic comparison job ac599b54-9c18-4b66-a844-e14b7ee87e15 with artifact/hash, provenance, distinct-frame, fail-closed, restoration, and final available-status evidence - Commit title: T19H make paired visual capture deterministic`
