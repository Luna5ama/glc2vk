# COMMON CONTRACT KNOWLEDGE

## OVERVIEW

Published shared library for capture metadata, commands, resources, neutral enums, replay CLI parsing,
and shader-source resolution. Changes affect capture plus both replay backends.

## WHERE TO LOOK

| Concern | Location | Notes |
|---------|----------|-------|
| Serialized model | `.../common/CaptureData.kt` | `PassInfo`, `Command`, metadata, data |
| Replay normalization | `CaptureData.kt` | Label balancing and binding aggregation |
| CLI parsing | `ReplayCliOptions.kt` | Shared positional and shader-override options |
| Shader selection/includes | `ShaderSourceResolver.kt` | Captured source, file/dir overrides, pass filters |
| Vulkan-neutral formats | `VkFormat.kt` and related enums | Shared wire values, not backend objects |

## ON-DISK CONTRACT

- A capture directory contains `resource_metadata.json` plus `resources.zip.xz`.
- Archive entries use stable names such as `image_<index>_<level>.bin` and `buffer_<index>.bin`.
- `CaptureData.save` and `load` shell out to `7z`; failures must preserve useful process output and a non-zero result.
- Treat serialized type/enum/name changes as compatibility changes. Old captures may be replayed by newer tools.
- Keep original/patched shader metadata aligned with the command/pass that consumes it.

## REPLAY SEMANTICS

- Preserve explicit `PushDebugGroup` / `PopDebugGroup` commands during normalization.
- Close unmatched pushed labels before replay finishes; do not discard the command history.
- Derive storage-buffer bindings from every command's `PassInfo`, not debug labels or ordering guesses.
- `parseReplayCliOptions` owns the shared grammar: capture directory first, optional numeric frame limit,
  `--shader-path`/`--shader-root` overrides, and repeatable `--shader-pass`.
- `ShaderSourceResolver` keeps captured indexed sources, file/directory override, pass-filter,
  and recursive-include behavior consistent across GL and VK.

## ANTI-PATTERNS

- Do not import LWJGL OpenGL, Caelum, GLFW, or backend resource objects into the shared schema.
- Do not silently rename archive entries or serialized fields.
- Do not duplicate CLI or shader-source rules in each backend.
- Do not infer bindings from label text.
- Do not assume `7z` is Gradle-managed; it is an external runtime prerequisite.

## VALIDATION

Run normalization tests, save/load a capture, and replay it through both backends.
Verify override directories with repeated pass filters and nested includes.
