# OPENGL CAPTURE KNOWLEDGE

## OVERVIEW

Published embedded library that observes OpenGL compute state, converts descriptors, patches shaders,
records commands/resources, and saves captures asynchronously.

## WHERE TO LOOK

| Concern | Location | Notes |
|---------|----------|-------|
| Capture lifecycle/state | `.../capture/Capture.kt` | Main hotspot; active singleton context |
| GLSL patching | `ShaderPatcher.kt` | Bindings, uniform blocks, Vulkan source |
| GL-to-VK mappings | `VkConversion.kt` | Exhaustive format/target/filter/wrap conversions |
| Compiler/test helper | `src/test/.../Utils.kt` | Invokes external `glslc` |
| Shader fixtures | `src/test/resources/*.csh` | Classpath-root test inputs |

## CAPTURE LIFECYCLE

- Hosts call `beginGlCapture(outputPath)`, capture-aware dispatch/debug functions, then `endGlCapture()`.
- Record commands only while the active capture context is present.
  Ordinary host dispatch behavior must remain unchanged otherwise.
- `endGlCapture()` returns the background save `Thread`. Callers that need a complete directory must `join` it.
- Preserve the caller's OpenGL state around inspection and readback.
- Pixel-pack capture must clear inherited `GL_PACK_SKIP_PIXELS` during reads, then restore the exact prior value.
  The runtime test locks this contract.

## SHADER AND BINDING CONTRACT

- Descriptor set 0 is samplers/images, set 1 is storage buffers, and set 2 is uniform buffers.
- Non-opaque value uniforms move into the generated uniform block.
  Keep captured values and patched declarations synchronized.
- Capture saves original GLSL, patched Vulkan GLSL, and SPIR-V where available. `glslang` is the runtime compiler.
- Extend mappings exhaustively for new GL formats, targets, filters, wrap/compare modes, uniform types, and samplers.
- Unsupported conversion is a hard error. A plausible silent fallback corrupts replay semantics.

## TESTS

- `VkConversionTest` and shader patch/compiler tests run in the normal module test task.
- Shader patch tests require `glslc` on `PATH` and a five-second successful compiler exit.
- `TextureCaptureRuntimeTest` requires OpenGL 4.6/GLFW and returns early unless `-Pvibris.runtimeTest=true`.
- Keep fixture resource paths classpath-rooted.
  The large `deferred13_b.csh` path is a manual harness, not an automated assertion.

## ANTI-PATTERNS

- Do not expose capture as a CLI; it is a host-embedded API.
- Do not leave pixel-pack, program, buffer, texture, image, or binding state modified after observation.
- Do not silently support an unknown enum by choosing a nearest format.
- Do not report a capture complete before its background save thread finishes.
- Do not touch the untracked `a.spv`; it is not a repository fixture or build input.

## VALIDATION

```powershell
.\gradlew.bat :vibris-capture:test
.\gradlew.bat :vibris-capture:test -Pvibris.runtimeTest=true
```