package dev.luna5ama.vibris.capture

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

@JvmRecord
data class CaptureStatus(
    val pending: Boolean,
    val active: Boolean,
    val saving: Boolean,
    val lastOutputPath: Path?,
    val lastError: String?
)

class CaptureManager {
    private var pendingCapture: CaptureRequest? = null
    private var activeCapture: CaptureSession? = null
    private var savingThread: Thread? = null
    private var lastOutputPath: Path? = null
    private var lastError: String? = null

    @Synchronized
    fun prepareSingleCapture(path: Path, passName: String) {
        pendingCapture = CaptureRequest(path, CaptureMode.SINGLE, passName, null)
        lastError = null
    }

    @Synchronized
    fun prepareMultiCapture(path: Path, programType: String) {
        val normalized = normalizeProgramType(programType)
        pendingCapture = CaptureRequest(path, CaptureMode.MULTI, null, normalized)
        lastError = null
    }

    @Synchronized
    fun startFrame() {
        val request = pendingCapture ?: return
        if (activeCapture != null) return

        activeCapture = CaptureSession(request)
        pendingCapture = null
        try {
            beginGlCapture(request.path)
        } catch (exception: RuntimeException) {
            lastError = exception.message
            activeCapture = null
            throw exception
        }
    }

    @Synchronized
    fun endFrame() {
        if (activeCapture != null) {
            finishActiveCapture()
        }
    }

    @Synchronized
    fun status(): CaptureStatus {
        return CaptureStatus(
            pending = pendingCapture != null,
            active = activeCapture != null,
            saving = savingThread?.isAlive == true,
            lastOutputPath = lastOutputPath,
            lastError = lastError
        )
    }

    @Synchronized
    fun dispatchCompute(
        computeSource: String,
        passName: String,
        x: Int,
        y: Int,
        z: Int
    ): Boolean {
        val session = activeCapture ?: return false
        if (!session.matches(passName)) return false

        captureGlDispatchCompute(createShaderInfo(computeSource, passName, session.request.programType), x, y, z)
        if (session.request.mode == CaptureMode.SINGLE) {
            finishActiveCapture()
        }
        return true
    }

    @Synchronized
    fun dispatchComputeIndirect(computeSource: String, passName: String, offset: Long): Boolean {
        val session = activeCapture ?: return false
        if (!session.matches(passName)) return false

        captureGlDispatchComputeIndirect(createShaderInfo(computeSource, passName, session.request.programType), offset)
        if (session.request.mode == CaptureMode.SINGLE) {
            finishActiveCapture()
        }
        return true
    }

    private fun createShaderInfo(computeSource: String, passName: String, programType: String?): ShaderInfo {
        return ShaderSourceContext(computeSource)
            .setIdentity(passName, programType, "$passName.csh")
            .also { it.patchShaderForVulkan() }
            .toShaderInfo()
    }

    private fun finishActiveCapture() {
        val session = activeCapture ?: return
        activeCapture = null
        try {
            savingThread = endGlCapture()
            lastOutputPath = session.request.path
        } catch (exception: RuntimeException) {
            lastError = exception.message
            throw exception
        }
    }

    private fun normalizeProgramType(programType: String): String {
        val normalized = programType.lowercase(Locale.ROOT)
        return when (normalized) {
            "prepare", "begin", "deferred", "composite" -> normalized
            else -> throw IllegalArgumentException("Unsupported capturemulti type: $programType")
        }
    }

    private enum class CaptureMode {
        SINGLE,
        MULTI
    }

    private data class CaptureRequest(
        val path: Path,
        val mode: CaptureMode,
        val passName: String?,
        val programType: String?
    )

    private class CaptureSession(val request: CaptureRequest) {
        fun matches(passName: String): Boolean {
            return when (request.mode) {
                CaptureMode.SINGLE -> request.passName == passName
                CaptureMode.MULTI -> Pattern.matches(
                    Pattern.quote(request.programType) + "([1-9][0-9]?)?(_[a-z])?",
                    passName
                )
            }
        }
    }

    companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        @JvmStatic
        fun defaultOutputPath(name: String): Path {
            return Path.of("vibris", "$name-${TIMESTAMP_FORMAT.format(LocalDateTime.now())}")
        }
    }
}