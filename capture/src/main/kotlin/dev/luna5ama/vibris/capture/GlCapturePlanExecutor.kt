package dev.luna5ama.vibris.capture

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.CaptureResult
import dev.vibris.api.ResourceCatalog
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.util.function.Function

object GlCapturePlanExecutor {
    @JvmStatic
    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
        resolveResource: Function<CapturePlan.Target, Int?>,
    ): CaptureResult = capture(plan, sink, frameId, cancellation, resolveResource) { target, glId, output ->
        when (target.kind) {
            ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
            ResourceCatalog.ResourceKind.TEXTURE,
            -> GlArtifactCapture.captureTexture(glId, target.mipLevel, target.layer, target.format, output)

            ResourceCatalog.ResourceKind.BUFFER -> GlArtifactCapture.captureBuffer(glId, output)
        }
    }

    internal fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
        resolveResource: Function<CapturePlan.Target, Int?>,
        captureArtifact: (CapturePlan.Target, Int, OutputStream) -> GlCaptureMetadata,
    ): CaptureResult {
        val targets = plan.targets().map { target ->
            ResolvedTarget(
                target,
                resolveResource.apply(target) ?: throw CaptureResourceNotFoundException(target.logicalName),
            )
        }
        val captured = linkedMapOf<String, ResourceCatalog.ResourceDescriptor>()
        for ((target, glId) in targets) {
            cancellation.throwIfCancellationRequested()
            val metadata = write(sink, target.fileName()) { output ->
                captureArtifact(target, glId, output)
            }
            val descriptor = descriptor(target, metadata, frameId)
            captured[target.artifactName] = descriptor
            if (target.format == CapturePlan.ArtifactFormat.RAW || target.format == CapturePlan.ArtifactFormat.BIN) {
                writeMetadata(sink, target.metadataFileName(), descriptor)
            }
        }
        return CaptureResult(frameId, captured)
    }

    private fun descriptor(
        target: CapturePlan.Target,
        metadata: GlCaptureMetadata,
        frameId: Long,
    ) = ResourceCatalog.ResourceDescriptor(
        target.logicalName,
        target.kind,
        metadata.width,
        metadata.height,
        metadata.depth,
        1,
        1,
        metadata.internalFormat,
        metadata.channelCount,
        metadata.scalarType,
        metadata.byteSize,
        frameId,
        target.logicalName,
    )

    private fun writeMetadata(
        sink: ArtifactSink,
        fileName: String,
        resource: ResourceCatalog.ResourceDescriptor,
    ) {
        val json = "{\"logical_name\":\"${escape(resource.logicalName)}\"" +
            ",\"kind\":\"${resource.kind}\"" +
            ",\"width\":${resource.width}" +
            ",\"height\":${resource.height}" +
            ",\"depth\":${resource.depth}" +
            ",\"internal_format\":\"${escape(resource.internalFormat)}\"" +
            ",\"channel_count\":${resource.channelCount}" +
            ",\"scalar_type\":\"${resource.scalarType}\"" +
            ",\"byte_size\":${resource.byteSize}" +
            ",\"frame_id\":${resource.frameId}}"
        write(sink, fileName) { output -> output.write(json.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun <T> write(sink: ArtifactSink, fileName: String, action: (OutputStream) -> T): T = try {
        sink.open(fileName).use(action)
    } catch (exception: IOException) {
        throw UncheckedIOException(exception)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class ResolvedTarget(val target: CapturePlan.Target, val glId: Int)
}