# Vibris engineering validation v2

Vibris exposes a breaking, request-scoped engineering-validation service. The wire protocol, MCP schemas,
configuration, workspace identity, durable-job state, delivery receipts, and delivery transaction manifests all use
schema version 2. There is no translation, migration, alias, dual-read, or dual-write path for earlier formats.

## Clean cutover

Build and run matching v2 copies of Iris, Vibris Core, and Vibris MCP. A client must send protocol major 2 and the exact
workspace ID on every request. Missing or different protocol versions fail with `UNSUPPORTED_VERSION` before any job
is submitted.

Persisted configuration, workspace identity, build receipts, and delivery transaction manifests whose
`schema_version` is not 2 are rejected and left byte-for-byte unchanged. Move old files aside manually if their
contents must be retained, then let the v2 service create new state. Vibris never deletes or converts old user data.

The authoritative workspace identity document has this shape:

```json
{
  "schema_version": 2,
  "workspace_id": "11111111-1111-4111-8111-111111111111"
}
```

Configuration additionally contains the loopback listen address, artifact root, capacity/TTL policy, and any
deployment-specific runtime paths. Relative paths resolve from the selected game directory exactly as written.

## MCP surface

The server publishes exactly eight tools:

- `vibris_get_status`
- `vibris_list_presets`
- `vibris_list_resources`
- `vibris_run_actions`
- `vibris_run_matrix`
- `vibris_run_recipe`
- `vibris_job`
- `vibris_artifacts`

Every call includes `worktree_root`. Calls that start work also include `preset_id`. Recipe requests include the typed
`recipe` discriminator. Job query, result, cancel, and resume operations exist only on `vibris_job`.

`vibris_get_status` reports the current runtime lease, pending recovery, queue, transitions, bounded job summaries,
last error and recovery action. `can_accept_job` is the admission gate: submit immediately when it is true, even if
another workspace owns the runtime. Core uses round-robin workspace turns. Consecutive child jobs from one durable
workflow share a turn for at most four jobs or two minutes, then the next waiting workspace runs. `can_start_job` only
reports that the runtime is idle enough to begin immediately and must not be used as a preflight gate. Status waits are
event-driven for `can_accept_job` or one job's terminal state and report whether the condition was satisfied or timed
out.

## Sources and settings

Omitting `source` selects the caller's workspace source. If `source` is supplied, `kind` is mandatory. Workspace and
commit sources are frozen before execution. Results include source identity, shader-content identity, effective shader
settings, origins, defaults, stable settings hash, and completion-time staleness facts.

State-changing validation snapshots the source, effective settings, scene, and temporal state. Terminal results carry
restoration receipts. If restoration cannot be proved, the job fails closed and status exposes the required recovery
action.

## Durable jobs

Long-running work is stored below `.vibris/jobs/<job_id>` with immutable request/source inputs, atomic state,
append-only events, immutable per-step receipts, and an immutable terminal result. Use `vibris_job` to query or cancel a
job and to resume only when its recorded phase is safe. Completed steps are never repeated; uncertain side effects are
not replayed. A retryable terminal child result pauses before checkpointing that step and is resubmitted on resume. A
non-retryable child result terminalizes the durable job as `failed`; later steps are never executed.

If the process stops, start the matching v2 service, inspect status and the job record, then request resume. A job that
cannot prove a safe continuation returns a terminal failure with recovery guidance.

## Compile validation and benchmarks

Run `vibris_run_recipe` with `recipe: "compile_validate"` to enumerate the complete compile catalog and compare stable
diagnostic fingerprints with an optional baseline. Compile validation does not require GPU warmup.

Matrix and A/B benchmark jobs use typed target, sibling, and sentinel cases. Decisions require measured noise,
confidence, order/reversal/drift checks, compile and visual gates, immutable provenance, and successful restoration.
The result explains every failed guardrail instead of producing an unqualified winner.

## Resources and action examples

Use `vibris_list_resources` to obtain logical texture/buffer names, exact pass IDs, available views, dimensions, formats,
and the catalog mapping hash. Physical texture suffixes are result metadata, not valid selectors.

Immediate texture and buffer capture:

```json
{
  "worktree_root": "I:/code/mcshaders/example",
  "preset_id": "720p",
  "actions": [
    {
      "dump_texture": {
        "resource": {"logical_name": "colortex0", "view": "TEXTURE_VIEW_CURRENT"},
        "format": "ARTIFACT_FORMAT_PNG",
        "artifact_name": "color"
      }
    },
    {
      "dump_buffer": {
        "resource": {"logical_name": "scene_ssbo"},
        "artifact_name": "scene"
      }
    }
  ]
}
```

Exact named-pass texture and buffer capture:

```json
{
  "worktree_root": "I:/code/mcshaders/example",
  "preset_id": "720p",
  "actions": [
    {
      "dump_texture_after_pass": {
        "pass_id": "deferred",
        "resource": {"logical_name": "colortex0", "view": "TEXTURE_VIEW_CURRENT"},
        "format": "ARTIFACT_FORMAT_PNG",
        "artifact_name": "deferred-color"
      }
    },
    {
      "dump_buffer_after_pass": {
        "pass_id": "deferred",
        "resource": {"logical_name": "scene_ssbo"},
        "artifact_name": "deferred-scene"
      }
    }
  ]
}
```

After-pass requests register before the next matching boundary and capture after the named pass completes. Receipts
include the exact pass, frame/occurrence, logical and physical resource identity, view, GL metadata, artifact paths,
hashes, manifest ID, and manifest hash. PNG output has the documented image orientation; BIN output preserves native
readback bytes.

## Artifacts

All generated output is owned by the worktree-local `.vibris/artifact` tree. `vibris_artifacts` lists, describes, and
deletes managed v2 manifests subject to workspace ownership. Manifests include request/job grouping, TTL, size, hashes,
and every output path. Capacity is reserved before capture; a request that would exceed policy fails before partial
output is published.

## Build and delivery

Use the repository Gradle/CMake builds, then run `tools/build-delivery.ps1` and `tools/package-delivery.ps1` when a
delivery is explicitly requested. Both scripts require schema-2 receipts/manifests and publish transactionally. Building
or packaging does not authorize deployment, Minecraft restart, launcher restart, or user-data cleanup.
