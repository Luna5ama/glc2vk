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
- Protected pre-existing changes: Vibris untracked `capture\a.spv` plus concurrently introduced tracked modifications to `core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt` and `core\src\test\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapterTest.kt`; Iris tracked `common\src\main\java\net\irisshaders\iris\vibris\IrisVibrisLifecycle.java` plus untracked `.codex\`, `.vibris\`, and `common\logs\`.

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
| `I:\code\vibris` | `core\src\main\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapter.kt` modified during T00 initialization | User/concurrent work | Do not modify or stage; before any later overlapping task, reconcile whether the change has been committed or request direction. |
| `I:\code\vibris` | `core\src\test\kotlin\dev\vibris\core\ThreadBoundVibrisRuntimeAdapterTest.kt` modified during T00 initialization | User/concurrent work | Do not modify or stage; before any later overlapping task, reconcile whether the change has been committed or request direction. |
| `I:\code\vibris` | Detached review worktrees under `I:\code\vibris-review-*` | User/review tooling | Do not modify, remove, or use as implementation targets. |
| `I:\code\Iris` | `.codex\` untracked | User/Codex runtime | Preserve and never stage. |
| `I:\code\Iris` | `.vibris\` untracked | Runtime artifacts | Preserve and never stage. |
| `I:\code\Iris` | `common\logs\` untracked | Runtime logs | Preserve and never stage. |
| `I:\code\Iris` | `common\src\main\java\net\irisshaders\iris\vibris\IrisVibrisLifecycle.java` modified after the T00 commit | User/concurrent work | Do not modify or stage; reconcile ownership before any later overlapping Iris task. |
| Both | Any later unrelated dirty file | User until proven otherwise | Stop, classify ownership, and keep it outside task staging. |

## Status board

| ID | Priority | Repository | Task | Status | Expected commit title |
|---|---|---|---|---|---|
| T00 | P0 | Vibris | Persist v2 execution ledger | DONE | `T00 persist engineering validation v2 ledger` |
| T01 | P0 | Vibris | Replace protocol with strict v2 wire contract | DONE | `T01 replace control protocol with strict v2` |
| T02 | P0 | Vibris | Publish compact typed MCP v2 tools | DONE | `T02 publish compact typed MCP v2 tools` |
| T03 | P0 | Vibris | Add truthful runtime lease and status waiting | READY | `T03 expose runtime lease and status transitions` |
| T04 | P0 | Vibris | Generalize durable resumable jobs | PENDING | `T04 add durable resumable workflow jobs` |
| T05 | P0 | Vibris | Add transactional restoration and recovery | PENDING | `T05 make runtime mutations transactional` |
| T06 | P0 | Vibris | Define effective shader settings contract | PENDING | `T06 expose resolved shader settings contract` |
| T07 | P0 | Iris | Implement effective settings in Iris host | PENDING | `T07 report effective shader settings from Iris` |
| T08 | P1 | Vibris | Return one ordered receipt per action | PENDING | `T08 return complete ordered action receipts` |
| T09 | P0 | Vibris | Define compile catalog runtime contract | PENDING | `T09 define compile validation catalog contract` |
| T10 | P0 | Iris | Emit complete Iris compile catalog | PENDING | `T10 emit Iris program compile catalog` |
| T11 | P0 | Vibris | Add compile_validate recipe | PENDING | `T11 add compile validation recipe` |
| T12 | P0 | Vibris | Expand immutable benchmark provenance | PENDING | `T12 expand benchmark provenance and stale checks` |
| T13 | P0 | Vibris | Enforce statistical benchmark guardrails | PENDING | `T13 enforce benchmark semantic guardrails` |
| T14 | P0 | Vibris | Replace artifacts with managed v2 manifests | PENDING | `T14 add managed artifact v2 lifecycle` |
| T15 | P0 | Vibris | Define named pass resource dump contract | PENDING | `T15 define named pass resource dump contract` |
| T16 | P0 | Iris | Implement named Iris pass boundary hooks | PENDING | `T16 capture resources after named Iris passes` |
| T17 | P0 | Vibris | Integrate after-pass texture and buffer jobs | PENDING | `T17 integrate after-pass resource dump jobs` |
| T18 | P0 | Vibris | Complete strict v2 cutover and documentation | PENDING | `T18 complete strict v2 cutover` |
| T19 | P0 | Vibris | Run offline integrated acceptance | PENDING | `T19 verify offline v2 integration` |
| T20 | P0 | Vibris/Iris | Run live two-worktree 720p acceptance | PENDING | `T20 record live v2 acceptance` |
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

### T03 — Add truthful runtime lease and status waiting

Status: `READY`

Dependencies: T02

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

- Pending.

### T04 — Generalize durable resumable jobs

Status: `PENDING`

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

- Pending.

### T05 — Add transactional restoration and recovery

Status: `PENDING`

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

- Pending.

### T06 — Define effective shader settings contract

Status: `PENDING`

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

- Pending.

### T07 — Implement effective settings in Iris host

Status: `PENDING`

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

- Pending external implementation and owner-repository receipt.

### T08 — Return one ordered receipt per action

Status: `PENDING`

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

- Pending.

### T09 — Define compile catalog runtime contract

Status: `PENDING`

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

- None known.

Evidence:

- Pending.

### T10 — Emit complete Iris compile catalog

Status: `PENDING`

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

- Pending external implementation and owner-repository receipt.

### T11 — Add compile_validate recipe

Status: `PENDING`

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

- Pending.

### T12 — Expand immutable benchmark provenance

Status: `PENDING`

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

- Pending.

### T13 — Enforce statistical benchmark guardrails

Status: `PENDING`

Dependencies: T12

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

- Pending.

### T14 — Replace artifacts with managed v2 manifests

Status: `PENDING`

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

- Pending.

### T15 — Define named pass resource dump contract

Status: `PENDING`

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

- Pending.

### T16 — Implement named Iris pass boundary hooks

Status: `PENDING`

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

- Pending external implementation and owner-repository receipt.

### T17 — Integrate after-pass texture and buffer jobs

Status: `PENDING`

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

- Hardware runtime tests require an OpenGL 4.6-capable local environment; a genuine hardware blocker must be recorded rather than bypassed.

Evidence:

- Pending.

### T18 — Complete strict v2 cutover and documentation

Status: `PENDING`

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

- None known.

Evidence:

- Pending.

### T19 — Run offline integrated acceptance

Status: `PENDING`

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

- Pending.

### T20 — Run live two-worktree 720p acceptance

Status: `PENDING`

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

- Requires a user-started matching v2 Minecraft/Iris runtime and two explicit shader worktrees; never satisfy this by autonomously restarting or deploying.

Evidence:

- Pending.

### T99 — Final integrated audit

Status: `PENDING`

Dependencies: T01, T02, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20

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
T00 -> T01 -> T02 -> T03 -> T04 -> T05 -> T06 -> T07 -> T08 -> T09
    -> T10 -> T11 -> T12 -> T13 -> T14 -> T15 -> T16 -> T17 -> T18
    -> T19 -> T20 -> T99
```

Queue order is authoritative and serial even where technical dependencies could allow parallel work.

## Global acceptance checklist

- [x] MCP publishes exactly the eight typed v2 tools and never duplicates the structured payload.
- [ ] No affected v1 compatibility parser, alias, adapter, fallback, migration, dual-read, or dual-write remains.
- [ ] Shared runtime ownership, queue, progress, error history, readiness, waits, cancellation, and recovery are truthful.
- [ ] Long jobs are durable, queryable, resumable when safe, and never duplicate completed or uncertain side effects.
- [ ] All state-mutating validation restores source, effective settings, scene, and temporal state with receipts.
- [ ] Preserve returns complete effective settings, origins, diff, and stable hash.
- [ ] Every input action produces exactly one ordered receipt.
- [ ] Compile validation reports every intended program/pass and baseline diagnostic changes without GPU warmup.
- [ ] Every result contains complete immutable provenance and correct shader-content stale semantics.
- [ ] Benchmark decisions enforce target/sibling/sentinel guardrails, measured noise, confidence, order, drift, compile, visual, provenance, and restoration gates.
- [ ] Artifact v2 supports TTL, hash manifests, request/job grouping, capacity prediction, ownership-safe list/detail/delete, and worktree-local paths.
- [ ] `dump_texture_after_pass` and `dump_buffer_after_pass` capture exact named pass boundaries with correct flip, visibility, bytes, artifacts, and GL-state restoration.
- [ ] Full Vibris native/Gradle and Iris build validation passes.
- [ ] Live two-worktree 720p acceptance passes without autonomous deployment or process restart.
- [ ] Every expected commit is present in the intended repository and branch.
- [ ] Protected and pre-existing user state remains untouched.
- [ ] Final worktree, branch, ledger, and Goal audits pass.

## Completion evidence

Record final artifact paths, hashes, test totals, live request/job receipts, repository heads, and task-to-commit mappings here during T99.

## Completion log

- `2026-08-11 - T00 - initialized and validated 22-task strict-v2 serial ledger; verified both repository identities and protected concurrent dirty state - Commit title: T00 persist engineering validation v2 ledger`
- `2026-08-11 - T01 - generated strict v2 Java/C++ protocol; Java 5/5 and native CTest 5/5 passed; v1 and missing versions reject as UNSUPPORTED_VERSION - Commit title: T01 replace control protocol with strict v2`
- `2026-08-11 - T02 - published exactly eight typed MCP v2 tools; native CTest 5/5 and direct schema/stdio fixtures passed; removed old control client and v1 stdio fixture dependency - Commit title: T02 publish compact typed MCP v2 tools`
