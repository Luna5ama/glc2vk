package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.NsightGpuTrace
import dev.vibris.protocol.v2.NsightGpuTraceReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.io.path.name

internal class NsightGpuTraceRunner(
    replayCaptureRoot: Path?,
    replayerRoot: Path? = null,
    bundledReplayJava: Path? = null,
) {
    private val replayCaptureRoot = replayCaptureRoot?.toAbsolutePath()?.normalize()
    private val replayerRoot = replayerRoot?.toAbsolutePath()?.normalize()
    private val bundledReplayJava = bundledReplayJava?.toAbsolutePath()?.normalize()

    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(
        command: NsightGpuTrace,
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        runtime: VibrisRuntimeAdapter,
        owner: RuntimeJobExecutor,
        prepared: CaptureJobExecutor.Prepared,
    ): Execution {
        validate(command)
        val root = replayCaptureRoot ?: throw failure(
            "Nsight GPU Trace is unavailable because replay_capture is not configured.",
        )
        val replayRuntime = resolveReplayRuntime(command)
        try {
            OwnedPathIdentity.createDirectoriesSafely(root)
        } catch (exception: IOException) {
            throw failure("The Vibris replay_capture directory is unavailable.", exception)
        }
        val rootIdentity = try {
            OwnedPathIdentity.captureDirectory(root)
        } catch (exception: IOException) {
            throw failure("The Vibris replay_capture directory is not an ordinary directory.", exception)
        }
        val temporary = root.resolve("${safeSegment(job.submission.jobId)}-${UUID.randomUUID()}")
        var original: Throwable? = null
        try {
            Files.createDirectory(temporary)
            return executeOwned(command, job, progress, deadline, runtime, owner, prepared, temporary, replayRuntime)
        } catch (failure: RuntimeJobExecutor.Failure) {
            original = failure
            throw failure
        } catch (exception: Exception) {
            val wrapped = failure(exception.message ?: "Nsight GPU Trace failed.", exception)
            original = wrapped
            throw wrapped
        } finally {
            try {
                ArtifactFiles.deleteTree(temporary, rootIdentity)
            } catch (cleanup: IOException) {
                if (original != null) {
                    original.addSuppressed(cleanup)
                } else {
                    throw failure("Temporary Nsight replay data could not be deleted.", cleanup)
                }
            }
        }
    }

    private fun executeOwned(
        command: NsightGpuTrace,
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        runtime: VibrisRuntimeAdapter,
        owner: RuntimeJobExecutor,
        prepared: CaptureJobExecutor.Prepared,
        temporary: Path,
        replayRuntime: ReplayRuntime,
    ): Execution {
        val capture = temporary.resolve("capture")
        val traceOutput = temporary.resolve("nsight")
        Files.createDirectory(traceOutput)

        waitForCaptureIdle(job, deadline, runtime, owner, progress)
        val internalAction = when (command.captureCase) {
            NsightGpuTrace.CaptureCase.PASS_ID -> RuntimeAction.CapturePass(command.passId, capture.toString())
            NsightGpuTrace.CaptureCase.CAPTURE_TYPE -> RuntimeAction.CaptureMulti(command.captureType, capture.toString())
            else -> throw failure("Nsight GPU Trace capture mode is missing.")
        }
        owner.await(runtime.executeAction(internalAction), job, deadline)
        waitForCapture(job, deadline, runtime, owner, progress, capture)

        progress.accept(JobStage.JOB_STAGE_GPU_TRACING)
        owner.probe().event(job.requestId, "GPU_TRACING")
        val log = temporary.resolve("nsight.log")
        val startedAt = FileTime.fromMillis(System.currentTimeMillis() - 2_000)
        val process = startNsight(command, capture, traceOutput, log, replayRuntime)
        val exitCode = awaitProcess(process, command.timeoutSeconds, job, deadline)
        val trace = newestTrace(traceOutput, startedAt)
            ?: throw failure("Nsight did not write a GPU Trace. ${logTail(log)}")
        val bundle = trace.parent.resolve("BASE")
        val missing = REQUIRED_BUNDLE_FILES.filterNot { ordinaryFile(bundle.resolve(it)) }
        if (missing.isNotEmpty()) {
            throw failure(
                "Nsight auto-export is incomplete; missing ${missing.joinToString()}. " +
                    "ngfx exit=$exitCode. ${logTail(log)}",
            )
        }

        val descriptorName = descriptorFileName(command)
        val logName = logFileName(command)
        val exported = REQUIRED_BUNDLE_FILES.associateWith { source ->
            exportFileName(command, source)
        }
        val descriptor = descriptor(command, exported, logName, exitCode).toByteArray(StandardCharsets.UTF_8)
        val sizes = LinkedHashMap<String, Long>()
        sizes[descriptorName] = descriptor.size.toLong()
        exported.forEach { (source, target) -> sizes[target] = Files.size(bundle.resolve(source)) }
        sizes[logName] = Files.size(log)
        prepared.reserveAdditionalArtifacts(sizes)

        prepared.transaction.open(descriptorName).use { output -> output.write(descriptor) }
        exported.forEach { (source, target) ->
            Files.newInputStream(bundle.resolve(source), NOFOLLOW_LINKS).use { input ->
                prepared.transaction.open(target).use(input::transferTo)
            }
        }
        Files.newInputStream(log, NOFOLLOW_LINKS).use { input ->
            prepared.transaction.open(logName).use(input::transferTo)
        }

        val generated = buildList {
            add(GeneratedArtifact(descriptorName, ArtifactKind.ARTIFACT_KIND_NSIGHT_GPU_TRACE,
                ArtifactFormat.ARTIFACT_FORMAT_JSON, "application/json"))
            exported.values.forEach { name ->
                add(GeneratedArtifact(name, ArtifactKind.ARTIFACT_KIND_NSIGHT_GPU_TRACE,
                    ArtifactFormat.ARTIFACT_FORMAT_TSV, "text/tab-separated-values; charset=utf-8"))
            }
            add(GeneratedArtifact(logName, ArtifactKind.ARTIFACT_KIND_EVENT_LOG,
                ArtifactFormat.ARTIFACT_FORMAT_TEXT, "text/plain; charset=utf-8"))
        }
        return Execution(receipt(command), generated)
    }

    private fun waitForCaptureIdle(
        job: CoreJob,
        deadline: Long,
        runtime: VibrisRuntimeAdapter,
        owner: RuntimeJobExecutor,
        progress: Consumer<JobStage>,
    ) {
        repeat(MAX_CAPTURE_WAIT_FRAMES) {
            val status = captureStatus(job, deadline, runtime, owner)
            if (!status.pending && !status.active && !status.saving) return
            owner.waitFrames(job, progress, deadline, 1)
        }
        throw failure("The internal replay capture service did not become idle.")
    }

    private fun waitForCapture(
        job: CoreJob,
        deadline: Long,
        runtime: VibrisRuntimeAdapter,
        owner: RuntimeJobExecutor,
        progress: Consumer<JobStage>,
        expected: Path,
    ) {
        repeat(MAX_CAPTURE_WAIT_FRAMES) {
            owner.waitFrames(job, progress, deadline, 1)
            val status = captureStatus(job, deadline, runtime, owner)
            status.error?.let { throw failure("Internal replay capture failed: $it") }
            if (!status.pending && !status.active && !status.saving) {
                if (status.output?.toAbsolutePath()?.normalize() != expected.toAbsolutePath().normalize()) {
                    throw failure("Internal replay capture completed with an unexpected output path.")
                }
                if (!ordinaryFile(expected.resolve("resource_metadata.json")) ||
                    !ordinaryFile(expected.resolve("resources.zip.xz"))
                ) {
                    throw failure("Internal replay capture output is incomplete.")
                }
                return
            }
        }
        throw failure("Internal replay capture did not finish before its frame wait limit.")
    }

    private fun captureStatus(
        job: CoreJob,
        deadline: Long,
        runtime: VibrisRuntimeAdapter,
        owner: RuntimeJobExecutor,
    ): CaptureState {
        val value = owner.await(runtime.executeAction(RuntimeAction.CaptureStatus), job, deadline)
        val root = Json.parseToJsonElement(value).jsonObject
        val output = root["lastOutputPath"]?.takeUnless { it === JsonNull }?.jsonPrimitive?.content?.let(Path::of)
        val error = root["lastError"]?.takeUnless { it === JsonNull }?.jsonPrimitive?.content
        return CaptureState(
            root.getValue("pending").jsonPrimitive.boolean,
            root.getValue("active").jsonPrimitive.boolean,
            root.getValue("saving").jsonPrimitive.boolean,
            output,
            error,
        )
    }

    private fun startNsight(
        command: NsightGpuTrace,
        capture: Path,
        output: Path,
        log: Path,
        replayRuntime: ReplayRuntime,
    ): Process {
        val java = replayRuntime.java
        val jar = replayRuntime.jar
        val ngfx = replayRuntime.ngfx
        val targetArguments = windowsCommandLine(listOf("-jar", jar.toString(), capture.toString(), command.replayFrames.toString()))
        val process = ProcessBuilder(
            ngfx.toString(),
            "--activity=GPU Trace Profiler",
            "--platform", "Windows",
            "--output-dir", output.toString(),
            "--exe", java.toString(),
            "--dir", jar.parent.toString(),
            "--args", targetArguments,
            "--env", "NSIGHT_SUGGEST_GRAPHICS_CAPTURE=0; NSIGHT_REPORT_REPLAY_WINDOW_INTERFERENCE=0;",
            "--no-timeout",
            "--start-after-ms", command.startAfterMs.toString(),
            "--max-duration-ms", command.maxDurationMs.toString(),
            "--allocated-timestamps", "1000000",
            "--architecture", command.architecture,
            "--metric-set-name", command.metricSetName,
            "--set-gpu-clocks", command.gpuClocks,
            "--auto-export",
            "--collect-screenshot", "0",
            "--trace-timeout", command.timeoutSeconds.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        if (command.timeEveryAction) process.command().add("--time-every-action")
        process.environment()["NSIGHT_SUGGEST_GRAPHICS_CAPTURE"] = "0"
        process.environment()["NSIGHT_REPORT_REPLAY_WINDOW_INTERFERENCE"] = "0"
        return try {
            process.start()
        } catch (exception: IOException) {
            throw failure("Nsight GPU Trace could not be launched.", exception)
        }
    }

    private fun awaitProcess(process: Process, timeoutSeconds: Int, job: CoreJob, deadline: Long): Int {
        val timeoutDeadline = saturatingAdd(System.nanoTime(), Duration.ofSeconds(timeoutSeconds.toLong()).toNanos())
        try {
            while (true) {
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) return process.exitValue()
                if (job.cancellation.token().isCancellationRequested()) {
                    throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Nsight GPU Trace was cancelled.")
                }
                if (System.nanoTime() >= minOf(deadline, timeoutDeadline)) {
                    throw RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT,
                        "Nsight GPU Trace timed out.",
                    )
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Nsight GPU Trace was interrupted.")
        } finally {
            terminateTree(process)
        }
    }

    private fun terminateTree(process: Process) {
        val descendants = process.toHandle().descendants().toList().asReversed()
        descendants.forEach(ProcessHandle::destroy)
        if (process.isAlive) process.destroy()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        if (process.isAlive) process.destroyForcibly()
    }

    private fun newestTrace(root: Path, earliest: FileTime): Path? = Files.walk(root).use { paths ->
        paths.filter { path ->
            ordinaryFile(path) && path.name.lowercase(Locale.ROOT).endsWith(".ngfx-gputrace") &&
                Files.getLastModifiedTime(path, NOFOLLOW_LINKS) >= earliest
        }.max(Comparator.comparing { path -> Files.getLastModifiedTime(path, NOFOLLOW_LINKS) }).orElse(null)
    }

    private fun resolveReplayRuntime(command: NsightGpuTrace): ReplayRuntime {
        val root = replayerRoot ?: throw failure(
            "Nsight GPU Trace is unavailable because the game-side Vibris replayer directory is not configured.",
        )
        val jar = requireOrdinaryFile(
            replayerJarPath(root, command.replayBackend),
            "Vibris ${command.replayBackend.uppercase(Locale.ROOT)} replayer jar",
        )
        return ReplayRuntime(resolveJava(), jar, resolveNsight())
    }

    private fun resolveJava(): Path {
        val candidates = buildList {
            bundledReplayJava?.let { add(it) }
            System.getenv("VIBRIS_REPLAY_JAVA")?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            System.getenv("JAVA_HOME")?.takeIf(String::isNotBlank)?.let { add(Path.of(it).resolve("bin/java.exe")) }
            System.getenv("PATH")?.split(';')?.filter(String::isNotBlank)?.forEach { add(Path.of(it).resolve("java.exe")) }
        }
        return candidates.firstOrNull(::ordinaryFile)?.toAbsolutePath()?.normalize()
            ?: throw failure(
                "A Java 24+ replay runtime was not found under vibris_root/runtime/java or through " +
                    "VIBRIS_REPLAY_JAVA, JAVA_HOME, or PATH.",
            )
    }

    private fun resolveNsight(): Path {
        System.getenv("VIBRIS_NSIGHT_NGFX")?.takeIf(String::isNotBlank)?.let { configured ->
            return requireOrdinaryFile(Path.of(configured), "VIBRIS_NSIGHT_NGFX")
        }
        val programFiles = System.getenv("ProgramFiles")?.let(Path::of)
            ?: throw failure("ProgramFiles is unavailable; Nsight Graphics could not be located.")
        val parent = programFiles.resolve("NVIDIA Corporation")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw failure("Nsight Graphics is not installed.")
        Files.list(parent).use { installs ->
            return installs.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.name.startsWith("Nsight Graphics ") }
                .sorted(Comparator.comparing<Path, String> { it.name }.reversed())
                .map { it.resolve("host/windows-desktop-nomad-x64/ngfx.exe") }
                .filter(::ordinaryFile)
                .findFirst()
                .orElseThrow { failure("Nsight Graphics ngfx.exe could not be located.") }
        }
    }

    private fun descriptor(
        command: NsightGpuTrace,
        exports: Map<String, String>,
        logName: String,
        exitCode: Int,
    ) = buildJsonObject {
        put("schema_version", 1)
        put("kind", "vibris_nsight_gpu_trace_bundle")
        put("capture_mode", if (command.hasPassId()) "single" else "multi")
        if (command.hasPassId()) put("pass_id", command.passId) else put("capture_type", command.captureType)
        put("replay_backend", command.replayBackend)
        put("architecture", command.architecture)
        put("metric_set_name", command.metricSetName)
        put("replay_frames", command.replayFrames)
        put("start_after_ms", command.startAfterMs)
        put("max_duration_ms", command.maxDurationMs)
        put("time_every_action", command.timeEveryAction)
        put("gpu_clocks", command.gpuClocks)
        put("ngfx_exit_code", exitCode)
        put("bundle_complete", true)
        put("replay_capture_discarded", true)
        put("raw_trace_discarded", true)
        putJsonObject("files") {
            exports.forEach(::put)
            put("NSIGHT_LOG", logName)
        }
        putJsonObject("measurement_contract") {
            put("evidence_marker", "^Replay$")
            put("pass_durations_only", true)
            put("exclude_copy_marker", true)
            put("exclude_unmarked_tail", true)
            put("whole_capture_is_shader_evidence", false)
            put("cpu_submission_is_shader_evidence", false)
            put("fraction_of_gpu_is_shader_evidence", false)
        }
    }.toString()

    private fun receipt(command: NsightGpuTrace): NsightGpuTraceReceipt = NsightGpuTraceReceipt.newBuilder()
        .setCaptureMode(if (command.hasPassId()) "single" else "multi")
        .apply {
            if (command.hasPassId()) passId = command.passId else captureType = command.captureType
        }
        .setReplayBackend(command.replayBackend)
        .setArchitecture(command.architecture)
        .setMetricSetName(command.metricSetName)
        .setReplayFrames(command.replayFrames)
        .setStartAfterMs(command.startAfterMs)
        .setMaxDurationMs(command.maxDurationMs)
        .setTimeEveryAction(command.timeEveryAction)
        .setGpuClocks(command.gpuClocks)
        .setReplayCaptureDiscarded(true)
        .setRawTraceDiscarded(true)
        .build()

    private fun logTail(path: Path): String = runCatching {
        val text = Files.readString(path)
        text.takeLast(2_048).replace(Regex("[\\r\\n]+"), " ").trim()
    }.getOrDefault("Nsight log is unavailable.")

    private fun requireOrdinaryFile(path: Path, label: String): Path = path.toAbsolutePath().normalize().also {
        if (!ordinaryFile(it)) throw failure("$label is missing or is not an ordinary file: $it")
    }

    private fun windowsCommandLine(arguments: List<String>): String = arguments.joinToString(" ", transform = ::quote)

    private fun quote(value: String): String {
        if (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' }) return value
        val result = StringBuilder("\"")
        var slashes = 0
        value.forEach { character ->
            when (character) {
                '\\' -> slashes++
                '"' -> {
                    repeat(slashes * 2 + 1) { result.append('\\') }
                    result.append('"')
                    slashes = 0
                }
                else -> {
                    repeat(slashes) { result.append('\\') }
                    slashes = 0
                    result.append(character)
                }
            }
        }
        repeat(slashes * 2) { result.append('\\') }
        return result.append('"').toString()
    }

    private fun ordinaryFile(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun failure(message: String, cause: Throwable? = null) = RuntimeJobExecutor.Failure(
        ErrorCode.ERROR_CODE_CAPTURE_FAILED,
        message,
    ).also { if (cause != null) it.addSuppressed(cause) }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    private fun saturatingAdd(left: Long, right: Long): Long = if (right > 0 && left > Long.MAX_VALUE - right) {
        Long.MAX_VALUE
    } else {
        left + right
    }

    data class Execution(
        val receipt: NsightGpuTraceReceipt,
        val artifacts: List<GeneratedArtifact>,
    )

    private data class CaptureState(
        val pending: Boolean,
        val active: Boolean,
        val saving: Boolean,
        val output: Path?,
        val error: String?,
    )

    private data class ReplayRuntime(
        val java: Path,
        val jar: Path,
        val ngfx: Path,
    )

    companion object {
        private const val MAX_CAPTURE_WAIT_FRAMES = 600
        private val SAFE_ARTIFACT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val BACKENDS = setOf("gl", "vk")
        private val CAPTURE_TYPES = setOf("prepare", "begin", "deferred", "composite")
        private val ARCHITECTURES = setOf(
            "Turing", "Ampere GA10x", "Orin GA10B", "Ada", "Thor GB10B", "Blackwell GB20x", "T25x GB20x",
        )
        private val GPU_CLOCKS = setOf("unaltered", "base", "boost")
        private val REQUIRED_BUNDLE_FILES = listOf(
            "REPRO_INFO.xls",
            "FRAME.xls",
            "GPUTRACE_FRAME.xls",
            "D3DPERF_EVENTS.xls",
            "GPUTRACE_REGIMES.xls",
        )

        fun validate(command: NsightGpuTrace) {
            require(command.captureCase != NsightGpuTrace.CaptureCase.CAPTURE_NOT_SET) { "Nsight capture mode is missing." }
            if (command.hasPassId()) require(command.passId.isNotBlank()) { "Nsight pass_id must not be blank." }
            if (command.hasCaptureType()) require(command.captureType in CAPTURE_TYPES) { "Nsight capture_type is unsupported." }
            require(SAFE_ARTIFACT.matches(command.artifactName)) { "Nsight artifact_name is invalid." }
            require(command.replayBackend in BACKENDS) { "Nsight replay_backend is unsupported." }
            require(command.architecture in ARCHITECTURES) { "Nsight architecture is unsupported." }
            require(command.metricSetName.isNotBlank()) { "Nsight metric_set_name must not be blank." }
            require(command.replayFrames in 2..10_000) { "Nsight replay_frames is invalid." }
            require(command.maxDurationMs in 1..600_000) { "Nsight max_duration_ms is invalid." }
            require(command.startAfterMs <= 600_000) { "Nsight start_after_ms is invalid." }
            require(command.timeoutSeconds in 30..3_600) { "Nsight timeout_seconds is invalid." }
            require(command.gpuClocks in GPU_CLOCKS) { "Nsight gpu_clocks is unsupported." }
        }

        fun descriptorFileName(command: NsightGpuTrace) = "${command.artifactName}.nsight.bundle.json"

        fun logFileName(command: NsightGpuTrace) = "${command.artifactName}.nsight.log"

        fun exportFileName(command: NsightGpuTrace, source: String) = "${command.artifactName}.nsight.$source"

        fun artifactFileNames(command: NsightGpuTrace): List<String> = buildList {
            add(descriptorFileName(command))
            REQUIRED_BUNDLE_FILES.forEach { add(exportFileName(command, it)) }
            add(logFileName(command))
        }

        internal fun replayerJarPath(root: Path, backend: String): Path {
            require(backend in BACKENDS) { "Nsight replay_backend is unsupported." }
            val fileName = if (backend == "vk") "replayer-vk.jar" else "replayer-gl.jar"
            return root.toAbsolutePath().normalize().resolve(fileName)
        }
    }
}
