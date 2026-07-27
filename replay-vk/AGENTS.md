# VULKAN REPLAY KNOWLEDGE

## OVERVIEW

Java 24 Vulkan/Caelum backend. It loads capture data, selects memory, stages resources,
compiles/reuses SPIR-V, builds compute pipelines, records commands, and presents through GLFW.

## WHERE TO LOOK

| Concern | Location | Notes |
|---------|----------|-------|
| Native/bootstrap loop | `.../replay/VKReplay.kt` | `VKReplayKt`; Vulkan 1.4 request |
| Replay state machine | `VKReplayInstance.kt` | Init, upload, execute, teardown hotspot |
| Resources/suballocation | `VKReplayResource.kt`, `MemorySuballocator.java` | Mixed Kotlin/Java ownership |
| Memory selection | `MemoryTypeManager.kt` | BAR-memory JVM toggle |
| Shader compilation | `VKReplayShaderCompiler.kt` | Captured SPIR-V reuse or external `glslang` |
| Stack helpers | `utils.kt` | Kotlin context parameters for `MemoryStack` |

## LIFECYCLE AND OWNERSHIP

- Load/extract GLFW before window and Vulkan instance creation; only `glfw3.dll` is packaged today.
- Initialize instance/device/swapchain, memory pools, descriptors, resources, pipelines,
  and synchronization in dependency order.
- Upload through staging resources before replay; do not release staging memory until the transfer has completed.
- Record barriers and resource transitions from captured command semantics, not backend guesses.
- Destroy commands, pipelines, descriptors, resources, allocations, swapchain/device/instance,
  and the native window in reverse ownership order.
- `MemorySuballocator.java` is intentional Java inside a Kotlin module; keep its contract aligned with Kotlin callers.

## SHADERS AND FLAGS

- Reuse captured SPIR-V only when the selected source/path permits it; otherwise patch and invoke external `glslang`.
- Shader compiler temporary directories currently have no cleanup path; do not treat them as source or stable cache.
- `-Dvibris.validation=true` enables Khronos validation and must appear before `-jar`.
- `-Dvibris.useBarMemory=true` changes memory-type preference; validate both choices when editing allocation logic.
- Sampler border color is currently an incomplete TODO with hardcoded opaque black, not a general policy.

## ANTI-PATTERNS

- Do not infer cross-platform native support from loader branches; `.so` and `.dylib` resources are absent.
- Do not add a silent Vulkan fallback for an unsupported captured GL feature.
- Do not free resources while queued work can still reference them.
- Do not collapse synchronization steps because one driver appears coherent.
- Do not claim test coverage for this backend; no tracked replay-vk tests exist.

## VALIDATION

```powershell
.\gradlew.bat :vibris-replay-vk:assemble
```

Run the optimized jar on a known capture with validation enabled and disabled.
Exercise shader overrides, repeated frames, presentation, shutdown, and the BAR-memory toggle where supported.