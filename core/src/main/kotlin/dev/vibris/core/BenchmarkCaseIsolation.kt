package dev.vibris.core

import dev.vibris.api.SceneContext
import dev.vibris.protocol.v1.BenchmarkBarrierReceipt
import dev.vibris.protocol.v1.BenchmarkBarrierStage
import dev.vibris.protocol.v1.ErrorCode
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class BenchmarkCaseIsolation private constructor(
    val workflowId: String,
    val caseId: String,
    val baselineSource: SourceRegistry.Lease,
    val baselineContext: SceneContext,
    baselineShaderSettings: Map<String, String>,
) {
    val baselineShaderSettings: Map<String, String> = java.util.Map.copyOf(baselineShaderSettings)
    private val receipts = ArrayList<BenchmarkBarrierReceipt>()
    private var restored = false

    fun sourcePublished(sourceUuid: String) {
        record(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SOURCE_PUBLISHED, sourceUuid = sourceUuid)
    }

    fun configApplied(sourceUuid: String, settings: Map<String, String>) {
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SOURCE_PUBLISHED)
        record(
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_CONFIG_APPLIED,
            sourceUuid = sourceUuid,
            configSha256 = BenchmarkProvenance.shaderConfigHash(settings),
        )
    }

    fun shaderReloaded(sourceUuid: String, settings: Map<String, String>) {
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_CONFIG_APPLIED)
        record(
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_RELOADED,
            sourceUuid = sourceUuid,
            configSha256 = BenchmarkProvenance.shaderConfigHash(settings),
        )
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun shaderGenerationConfirmed(sourceUuid: String, inspection: JsonObject) {
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_RELOADED)
        val patched = inspection["patched_shader"] as? JsonObject
        val available = (patched?.get("available") as? JsonPrimitive)?.content == "true"
        val sha256 = (patched?.get("sha256") as? JsonPrimitive)?.content.orEmpty()
        val generation = (patched?.get("generation") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (!available || !sha256.matches(Regex("[0-9a-f]{64}")) || generation <= 0) {
            throw barrierFailure("The runtime did not confirm the patched shader generation.")
        }
        record(
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_GENERATION_CONFIRMED,
            sourceUuid = sourceUuid,
            shaderGeneration = generation,
            detail = sha256,
        )
    }

    fun warmupStarted() {
        if (lastStage() == BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_GENERATION_CONFIRMED) {
            record(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_STARTED)
        }
    }

    fun warmupCompleted() {
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_STARTED)
        record(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_COMPLETED)
    }

    fun sampleStarted() {
        if (lastStage() == BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_GENERATION_CONFIRMED) {
            warmupStarted()
            warmupCompleted()
        }
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_COMPLETED)
        record(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_STARTED)
    }

    fun sampleCompleted() {
        requireLast(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_STARTED)
        record(BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_COMPLETED)
    }

    fun stateRestored() {
        if (restored) return
        record(
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_STATE_RESTORED,
            sourceUuid = baselineSource.uuid,
            configSha256 = BenchmarkProvenance.shaderConfigHash(baselineShaderSettings),
        )
        restored = true
    }

    fun restored(): Boolean = restored

    @Throws(RuntimeJobExecutor.Failure::class)
    fun requireComplete() {
        if (!restored || receipts.map(BenchmarkBarrierReceipt::getStage) != REQUIRED_STAGES) {
            throw barrierFailure("The benchmark case did not cross every required isolation barrier.")
        }
    }

    fun receipts(): List<BenchmarkBarrierReceipt> = java.util.List.copyOf(receipts)

    private fun record(
        stage: BenchmarkBarrierStage,
        sourceUuid: String = "",
        configSha256: String = "",
        shaderGeneration: Long = 0,
        detail: String = "",
    ) {
        receipts.add(
            BenchmarkBarrierReceipt.newBuilder()
                .setCaseId(caseId)
                .setStage(stage)
                .setOrdinal(receipts.size + 1)
                .setSourceUuid(sourceUuid)
                .setConfigSha256(configSha256)
                .setShaderGeneration(shaderGeneration)
                .setDetail(detail)
                .build(),
        )
    }

    private fun requireLast(expected: BenchmarkBarrierStage) {
        if (lastStage() != expected) {
            throw barrierFailure("Benchmark barrier order is invalid before ${expected.name}.")
        }
    }

    private fun lastStage(): BenchmarkBarrierStage = receipts.lastOrNull()?.stage
        ?: BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_UNSPECIFIED

    private fun barrierFailure(message: String) = RuntimeJobExecutor.Failure(
        ErrorCode.BENCHMARK_BARRIER_FAILED,
        message,
    )

    companion object {
        private val REQUIRED_STAGES = listOf(
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SOURCE_PUBLISHED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_CONFIG_APPLIED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_RELOADED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_GENERATION_CONFIRMED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_STARTED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_COMPLETED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_STARTED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_COMPLETED,
            BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_STATE_RESTORED,
        )

        @Throws(RuntimeJobExecutor.Failure::class)
        fun begin(
            job: CoreJob,
            activator: SourceActivator,
            activeContext: SceneContext?,
            activeShaderSettings: Map<String, String>?,
        ): BenchmarkCaseIsolation? {
            if (!job.submission.hasBenchmarkCase()) return null
            val identity = job.submission.benchmarkCase
            val loadCases = job.submission.actions.actionsList.filter { it.hasLoadShader() }
            if (
                runCatching { UUID.fromString(identity.workflowId) }.isFailure ||
                identity.caseId.isBlank() || loadCases.size != 1 ||
                loadCases.single().loadShader.caseId != identity.caseId
            ) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.BENCHMARK_BARRIER_FAILED,
                    "Benchmark case identity is incomplete or inconsistent.",
                )
            }
            val baseline = try {
                activator.retainActive()
            } catch (failure: SourceActivator.Failure) {
                throw RuntimeJobExecutor.Failure(failure.code, failure.message)
            } ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.BENCHMARK_STATE_UNAVAILABLE,
                "No Core-owned source is available for post-matrix restoration.",
            )
            if (activeContext == null || activeShaderSettings == null) {
                activator.releaseRetained(baseline)
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.BENCHMARK_STATE_UNAVAILABLE,
                    "The pre-matrix scene or shader config has not been observed by Vibris.",
                )
            }
            return BenchmarkCaseIsolation(
                identity.workflowId,
                identity.caseId,
                baseline,
                activeContext,
                activeShaderSettings,
            )
        }
    }
}