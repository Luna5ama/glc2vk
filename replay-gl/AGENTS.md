# OPENGL REPLAY KNOWLEDGE

## OVERVIEW

Java 24 backend that loads a capture into a hidden GLFW OpenGL 4.6 context, rebuilds resources/programs,
executes normalized commands, and packages an optimized fat JAR.

## WHERE TO LOOK

| Concern | Location | Notes |
|---------|----------|-------|
| CLI/bootstrap | `src/main/kotlin/dev/luna5ama/vibris/replay/GLReplay.kt` | Main class `GLReplayKt` |
| Command lifecycle | `GLReplayInstance.kt` | Load, execute, reset, destroy |
| Buffers/images | `GLReplayResource.kt` | Captured resource recreation and binding |
| Shader compilation | `GLReplayShader.kt` | Override/captured source and failure dumps |
| Validation | `GLReplayValidation.kt` | Captured-vs-current binding checks |

## EXECUTION CONTRACT

- Parse arguments through common `parseReplayCliOptions`; do not fork the CLI grammar here.
- Initialize the OpenGL 4.6 context before creating replay resources or compiling programs.
- At each execute, reset captured resource state, apply normalized commands in order,
  and issue the required memory barriers.
- Preserve debug-label commands; use command `PassInfo` for resource binding provenance.
- Destroy programs, resources, and GLFW state exactly once on every exit path.
- Shader override failures dump a temporary `.comp.glsl` file; keep the diagnostic path visible to the caller.

## TESTS

- `ReplayCommandNormalizationTest` is pure logic and locks label/binding semantics shared with `common`.
- `ReplayGLRuntimeTest` captures and replays real compute work, then reads the GPU buffer back.
- The runtime test returns early unless `-Pvibris.runtimeTest=true`; default green is not GPU coverage.
- Runtime capture output is generated under `build/runtime-capture-test/` and is not source.

## ANTI-PATTERNS

- Do not use Vulkan replay classes merely because both modules share the same Kotlin package.
- Do not skip reset/barrier work when replaying a repeated frame.
- Do not make resource teardown depend only on normal window closure.
- Do not treat thin jars as standalone; run the `-fatjar-optimized.jar` artifact.
- Do not assume non-Windows LWJGL natives are packaged; the build declares Windows natives.

## VALIDATION

```powershell
.\gradlew.bat :vibris-replay-gl:test
.\gradlew.bat :vibris-replay-gl:test -Pvibris.runtimeTest=true
.\gradlew.bat :vibris-replay-gl:assemble
```

Run the optimized jar against a known capture and repeat several frames.
Close the window and verify clean resource teardown.