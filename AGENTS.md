# PROJECT KNOWLEDGE BASE

**Generated:** 2026-07-26
**Commit:** f0acfc2
**Branch:** vibris

## OVERVIEW

vibris captures OpenGL compute work into a portable on-disk representation and replays it through OpenGL or Vulkan.
It is a Kotlin/Java Gradle multi-project with shared schema, an embedded capture library, and two replay backends.

## STRUCTURE

```text
vibris/
├── common/     # Serialized capture contract, I/O, replay CLI, shader-source resolution
├── capture/    # OpenGL capture hooks, GL-to-Vulkan conversion, shader patching
├── mcp/        # Published authenticated loopback transport and stdio MCP bridge
├── replay-gl/  # Java 24 OpenGL replay executable and GPU integration tests
├── replay-vk/  # Java 24 Vulkan/Caelum replay executable, native resource handling
└── buildSrc/   # Java 21/Kotlin compiler and publication convention plugins
```

Physical module directories map to Gradle projects named `:vibris-common`, `:vibris-capture`, `:vibris-mcp`,
`:vibris-replay-gl`, and `:vibris-replay-vk`.

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Capture file schema and commands | `common/.../CaptureData.kt` | Cross-backend compatibility contract |
| Replay CLI / overrides | `common/.../ReplayCliOptions.kt` | See also `ShaderSourceResolver.kt` |
| OpenGL capture lifecycle | `capture/.../Capture.kt` | `beginGlCapture` / capture-aware dispatch / `endGlCapture` |
| GLSL-to-Vulkan patching | `capture/.../ShaderPatcher.kt` | Descriptor sets and uniform-block rewrites |
| MCP transport and stdio bridge | `mcp/.../CaptureControlServer.kt`, `mcp/src/main/python/vibris_capture_mcp.py` | Authenticated HTTP transport and stdio JSON-RPC/MCP |
| OpenGL replay | `replay-gl/.../GLReplay.kt`, `GLReplayInstance.kt` | Hidden GLFW OpenGL 4.6 context |
| Vulkan replay | `replay-vk/.../VKReplay.kt`, `VKReplayInstance.kt` | Caelum backend, Vulkan resources and sync |
| Vulkan resources | `replay-vk/.../` | Mixed Kotlin/Java allocation code |
| Shared build rules | `buildSrc/src/main/kotlin/` | Precompiled `buildsrc.convention.*` plugins |

## CODE MAP

| Symbol | Location | Role |
|--------|----------|------|
| `CaptureData` | `common/.../CaptureData.kt` | In-memory/on-disk contract |
| `CaptureMetadata` / `Command` | `common/.../CaptureData.kt` | Commands, resources, labels, bindings |
| `ShaderSourceResolver` | `common/.../ShaderSourceResolver.kt` | Override selection and includes |
| `CaptureContext` | `capture/.../Capture.kt` | GL inspection and recording |
| `ShaderSourceContext` | `capture/.../ShaderPatcher.kt` | Vulkan-compatible shader patching |
| `GLReplayInstance` | `replay-gl/.../GLReplayInstance.kt` | GL resource binding and execution |
| `VKReplayInstance` | `replay-vk/.../VKReplayInstance.kt` | Vulkan upload and frame lifecycle |

CodeGraph is not initialized here and LSP was unavailable from the sibling-root session.
Reference centrality is therefore unmeasured.

## CONVENTIONS

- `.editorconfig` is authoritative and non-default: UTF-8, CRLF, 4 spaces, 120 columns, and no final newline.
- Kotlin uses official style plus project IntelliJ settings.
  Do not run a formatter that silently changes trailing-comma or wrapping policy.
- Base convention plugins target Java 21; both replay executables explicitly target Java 24.
- Use prefixed Gradle project paths (`:vibris-capture:*`), never physical-directory paths such as `:capture:*`.
- `common`, `capture`, and `mcp` are published libraries; replay modules package optimized executable fat JARs.
- Both replay modules intentionally use package `dev.luna5ama.vibris.replay`; module location distinguishes the backend.

## ANTI-PATTERNS (THIS PROJECT)

- Do not silently default unsupported GL/Vulkan formats, targets, filters, wrap modes, or uniform types.
  Extend exhaustive mappings and tests.
- Do not infer storage bindings from debug labels.
  Preserve label commands and derive bindings from each command's `PassInfo`.
- Do not treat a green default test run as GPU coverage.
  Hardware tests return early unless `-Pvibris.runtimeTest=true` is set.
- Do not treat `capture` as an executable.
  It is an embedded API; callers may need to join the thread returned by `endGlCapture()`.
- Do not use stale `glc2vk-*` artifacts in `build/libs`; current outputs are named `vibris-*`.

## UNIQUE STYLES

- Capture directories contain `resource_metadata.json` plus `resources.zip.xz`.
  Capture and replay shell out to external tools rather than Gradle-managed libraries.
- Shader patching reserves set 0 for samplers/images, set 1 for storage buffers, and set 2 for uniform buffers.
- Replay normalization preserves explicit debug labels and closes dangling pushed labels before execution.
- Packaging is Windows-oriented: LWJGL Windows natives and only `replay-vk/src/main/resources/glfw3.dll` are present.

## COMMANDS

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat test -Pvibris.runtimeTest=true
.\gradlew.bat :vibris-capture:test
.\gradlew.bat :vibris-replay-gl:assemble
.\gradlew.bat :vibris-replay-vk:assemble
.\gradlew.bat :vibris-common:publishToMavenLocal :vibris-capture:publishToMavenLocal :vibris-mcp:publishToMavenLocal
```

Run an optimized replay JAR with:
`java -jar <module>\build\libs\*-fatjar-optimized.jar <capture-dir> [frame-limit]`.
Add `--shader-path` and repeated `--shader-pass` arguments as needed.

## NOTES

- External executables: `7z` for archives, `glslang` for runtime compilation,
  and `glslc` for patcher tests.
- Runtime tests need an OpenGL 4.6-capable GPU/driver. The Vulkan backend requests Vulkan 1.4.
- `-Dvibris.validation=true` enables Vulkan validation.
  `-Dvibris.useBarMemory=true` toggles BAR-memory selection. Put JVM properties before `-jar`.
- README's sibling `gl-wrapper` instruction is not wired through `includeBuild`.
  The current build resolves Maven coordinates from configured repositories.
- No checked-in CI or automated release workflow exists.
  The pre-existing untracked `capture/a.spv` is user data, not a build input.