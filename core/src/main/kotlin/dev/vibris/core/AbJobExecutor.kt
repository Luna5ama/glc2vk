package dev.vibris.core

import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
import dev.vibris.protocol.v1.JobStage
import java.io.IOException
import java.util.function.Consumer

internal class AbJobExecutor(
    private val owner: RuntimeJobExecutor,
    private val captures: CaptureJobExecutor,
    private val activator: SourceActivator,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): JobResult {
        val recipe = job.submission.recipe.abCompare
        val baseline = source(job, recipe.baseline.sourceUuid)
        val candidate = source(job, recipe.candidate.sourceUuid)
        if (baseline === candidate) {
            throw RuntimeJobExecutor.Failure(ErrorCode.INVALID_SOURCE_UUID, "A/B sources must be distinct.")
        }

        val baselineReload: ReloadResult = owner.activateSource(job, baseline, progress, deadline)
        owner.applyContext(job, progress, deadline)
        owner.reset(job, progress, deadline)
        owner.waitFrames(job, progress, deadline, recipe.warmupFrames)

        val ab = captures.prepareAb(job, owner.runtime().getResourceCatalog(), baselineReload.diagnostics())
        val prepared = ab.prepared
        try {
            prepared.use {
                val a: CaptureResult = owner.capture(job, progress, deadline, prepared, ab.program.baseline)
                val candidateReload = owner.activateSource(job, candidate, progress, deadline)
                prepared.addDiagnostics(candidateReload.diagnostics())
                activator.release(listOf(baseline))
                owner.applyContext(job, progress, deadline)
                owner.reset(job, progress, deadline)
                owner.waitFrames(job, progress, deadline, recipe.warmupFrames)
                val b: CaptureResult = owner.capture(job, progress, deadline, prepared, ab.program.candidate)

                progress.accept(JobStage.JOB_STAGE_COMPARING)
                owner.probe().event(job.requestId, "COMPARING")
                val comparison: AbComparisonResult = captures.compare(job, ab)
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                owner.probe().event(job.requestId, "WRITING_ARTIFACTS")
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                owner.probe().event(job.requestId, "FINALIZING")
                return captures.commit(job, prepared, listOf(a, b), comparison)
            }
        } catch (exception: IOException) {
            throw CaptureJobExecutor.failure(exception)
        }
    }

    companion object {
        @Throws(RuntimeJobExecutor.Failure::class)
        private fun source(job: CoreJob, uuid: String): SourceRegistry.Lease =
            job.sources.firstOrNull { it.uuid().equals(uuid, ignoreCase = true) }
                ?: throw RuntimeJobExecutor.Failure(
                    ErrorCode.INVALID_SOURCE_UUID,
                    "A/B recipe references an unprepared source.",
                )
    }
}