package dev.vibris.core

import dev.vibris.protocol.v1.ActionResult
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.ArtifactKind
import dev.vibris.protocol.v1.BenchmarkBarrierReceipt
import dev.vibris.protocol.v1.JobActionKind
import dev.vibris.protocol.v1.SubmitJob
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal data class GeneratedArtifact(
    val fileName: String,
    val kind: ArtifactKind,
    val format: ArtifactFormat,
    val mediaType: String,
)

internal object ProfileResultArtifacts {
    const val JSON_FILE = "profile-result.json"
    const val CSV_FILE = "profile-result.csv"

    fun requested(submission: SubmitJob): Boolean =
        submission.hasResultArtifacts() &&
            (submission.resultArtifacts.json || submission.resultArtifacts.csv)

    fun write(
        submission: SubmitJob,
        transaction: ArtifactManager.JobTransaction,
        actionResults: List<ActionResult>,
        barriers: List<BenchmarkBarrierReceipt> = emptyList(),
    ): List<GeneratedArtifact> {
        if (!requested(submission)) return emptyList()
        val document = document(submission, actionResults, barriers)
        val outputs = ArrayList<GeneratedArtifact>()
        if (submission.resultArtifacts.json) {
            write(transaction, JSON_FILE, document.toString() + "\n")
            outputs.add(
                GeneratedArtifact(
                    JSON_FILE,
                    ArtifactKind.ARTIFACT_KIND_PROFILE_RESULT,
                    ArtifactFormat.ARTIFACT_FORMAT_JSON,
                    "application/json",
                ),
            )
        }
        if (submission.resultArtifacts.csv) {
            write(transaction, CSV_FILE, csv(document, submission.resultArtifacts.convertedUnitsList.toSet()))
            outputs.add(
                GeneratedArtifact(
                    CSV_FILE,
                    ArtifactKind.ARTIFACT_KIND_PROFILE_RESULT,
                    ArtifactFormat.ARTIFACT_FORMAT_CSV,
                    "text/csv; charset=utf-8",
                ),
            )
        }
        return outputs
    }

    internal fun document(
        submission: SubmitJob,
        actionResults: List<ActionResult>,
        barriers: List<BenchmarkBarrierReceipt> = emptyList(),
    ): JsonObject {
        val actions = submission.actions.actionsList
        val orderedResults = actionResults.sortedBy { it.actionIndex }
        val loadIndices = actions.indices.filter { actions[it].hasLoadShader() }
        var passed = 0
        var failedCount = 0
        var incomplete = 0
        var withMetrics = 0
        val cases = buildJsonArray {
            loadIndices.forEachIndexed { caseIndex, firstAction ->
                val lastAction = loadIndices.getOrNull(caseIndex + 1) ?: actions.size
                val load = actions[firstAction].loadShader
                val related = orderedResults.filter { it.caseId == load.caseId }
                val parsed = related.map { it to parse(it.json) }
                val failure = parsed.firstOrNull { (_, payload) -> isFailed(payload) }?.second
                val provenance = parsed.firstOrNull { (result, _) ->
                    result.kind == JobActionKind.JOB_ACTION_KIND_LOAD_SHADER
                }?.second?.let { payload -> (payload as? JsonObject)?.get("provenance") }
                val provenanceComplete = ((provenance as? JsonObject)?.get("complete") as? JsonPrimitive)
                    ?.content == "true"
                val metricResult = parsed.firstOrNull { (result, payload) ->
                    result.kind == JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS && hasGpuSamples(payload)
                }?.second
                val metricsSeen = parsed.any { (result, _) ->
                    result.kind == JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS
                }
                if (metricResult != null) ++withMetrics
                val error: JsonElement
                val status: String
                when {
                    failure != null -> {
                        ++failedCount
                        status = "failed"
                        error = failure
                    }
                    metricResult == null || !provenanceComplete -> {
                        ++incomplete
                        status = "incomplete"
                        error = if (metricResult == null) noGpuSamples(load.caseId, metricsSeen)
                            else incompleteProvenance(load.caseId)
                    }
                    else -> {
                        ++passed
                        status = "passed"
                        error = JsonNull
                    }
                }
                val frames = actions.subList(firstAction, lastAction)
                    .firstOrNull { it.hasGetGpuMetrics() }
                    ?.getGpuMetrics
                    ?.frames ?: 0
                val warmupFrames = actions.subList(firstAction, lastAction)
                    .filter { it.hasWaitFrames() }
                    .sumOf { it.waitFrames.frameCount.toLong() }
                add(buildJsonObject {
                    put("case_id", load.caseId)
                    put("source_id", load.sourceId)
                    put("config_id", load.configId)
                    put("status", status)
                    put("error", error)
                    put("frames", frames)
                    put("warmup_frames", warmupFrames)
                    put(
                        "metrics",
                        metricResult?.let {
                            convertedMetrics(it, submission.resultArtifacts.convertedUnitsList.toSet())
                        } ?: JsonNull,
                    )
                    put("provenance", provenance ?: JsonNull)
                    put("barriers", buildJsonArray {
                        barriers.filter { it.caseId == load.caseId }.forEach { add(barrier(it)) }
                    })
                    put("action_results", buildJsonArray {
                        parsed.filter { (result, _) ->
                            result.kind != JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS
                        }.forEach { (result, payload) ->
                            add(actionResult(result, payload, firstAction))
                        }
                    })
                })
            }
        }
        val requested = loadIndices.size
        return buildJsonObject {
            put("artifact_schema_version", 1)
            put("attempt", submission.resultArtifacts.attempt)
            put("previous_attempts", buildJsonArray {
                submission.resultArtifacts.previousAttemptsList.forEach { diagnostic ->
                    add(buildJsonObject {
                        put("attempt", diagnostic.attempt)
                        put("status", diagnostic.status)
                        put("error_code", diagnostic.errorCode)
                        put("message", diagnostic.message)
                        put("retryable", diagnostic.retryable)
                    })
                }
            })
            put("success", failedCount == 0 && incomplete == 0)
            put("kind", submission.resultArtifacts.kind)
            put(
                "status",
                if (incomplete != 0) "incomplete" else if (failedCount == 0) "completed" else "completed_with_failures",
            )
            put("result_detail", "full")
            put("gpu_timing_unit", "ns")
            put("requested_cases", requested)
            put("completed_cases", passed + failedCount)
            put("cases_with_metrics", withMetrics)
            put("missing_cases", requested - withMetrics)
            put("failed_cases", failedCount)
            put("retried_cases", 0)
            put("passed", passed)
            put("failed", failedCount)
            put("incomplete", incomplete)
            put("cases", cases)
            put("benchmark_barriers", buildJsonArray { barriers.forEach { add(barrier(it)) } })
            put("raw_action_results", buildJsonArray {
                orderedResults.forEach { result -> add(actionResult(result, parse(result.json), 0)) }
            })
        }
    }

    private fun convertedMetrics(payload: JsonElement, units: Set<String>): JsonElement {
        if (units.isEmpty() || payload !is JsonObject) return payload
        val timings = payload["gpuTimings"] as? JsonObject ?: return payload
        return JsonObject(payload.toMutableMap().apply {
            put("gpuTimings", JsonObject(timings.mapValues { (_, statistics) ->
                if (statistics !is JsonObject) return@mapValues statistics
                JsonObject(statistics.toMutableMap().apply {
                    statistics.forEach { (name, value) ->
                        val number = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                        if ("us" in units) put("${name}_us", JsonPrimitive(number / 1_000.0))
                        if ("ms" in units) put("${name}_ms", JsonPrimitive(number / 1_000_000.0))
                    }
                })
            }))
        })
    }

    private fun csv(document: JsonObject, units: Set<String>): String {
        val output = StringBuilder("case_id,source_id,config_id,status,error_code,error_message,pass,statistic,value_ns")
        if ("us" in units) output.append(",value_us")
        if ("ms" in units) output.append(",value_ms")
        output.append('\n')
        val cases = document["cases"] ?: return output.toString()
        for (caseValue in cases as kotlinx.serialization.json.JsonArray) {
            val case = caseValue as JsonObject
            val prefix = listOf(
                text(case["case_id"]), text(case["source_id"]), text(case["config_id"]), text(case["status"]),
                text((case["error"] as? JsonObject)?.get("error_code")),
                text((case["error"] as? JsonObject)?.get("message")),
            )
            val timings = ((case["metrics"] as? JsonObject)?.get("gpuTimings") as? JsonObject)
            var emitted = false
            timings?.forEach { (pass, statistics) ->
                (statistics as? JsonObject)?.forEach { (statistic, value) ->
                    if (statistic.endsWith("_us") || statistic.endsWith("_ms")) return@forEach
                    val number = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                    appendCsvRow(output, prefix + listOf(pass, statistic, number.toString()), number, units)
                    emitted = true
                }
            }
            if (!emitted) appendCsvRow(output, prefix + listOf("", "", ""), null, units)
        }
        return output.toString()
    }

    private fun appendCsvRow(output: StringBuilder, fields: List<String>, valueNs: Double?, units: Set<String>) {
        output.append(fields.joinToString(",") { csvField(it) })
        if ("us" in units) output.append(',').append(valueNs?.div(1_000.0)?.toString().orEmpty())
        if ("ms" in units) output.append(',').append(valueNs?.div(1_000_000.0)?.toString().orEmpty())
        output.append('\n')
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }

    private fun text(value: JsonElement?): String =
        when (value) {
            is JsonPrimitive -> if (value.isString) value.content else value.toString()
            else -> ""
        }

    private fun actionResult(result: ActionResult, payload: JsonElement, baseIndex: Int): JsonObject =
        buildJsonObject {
            put("action_index", result.actionIndex - baseIndex)
            put("case_id", result.caseId)
            put("kind", result.kind.name.removePrefix("JOB_ACTION_KIND_").lowercase())
            put("result", payload)
        }

    private fun barrier(receipt: BenchmarkBarrierReceipt): JsonObject = buildJsonObject {
        put("case_id", receipt.caseId)
        put("stage", receipt.stage.name.removePrefix("BENCHMARK_BARRIER_STAGE_").lowercase())
        put("ordinal", receipt.ordinal)
        put("source_uuid", receipt.sourceUuid)
        put("config_sha256", receipt.configSha256)
        put("shader_generation", receipt.shaderGeneration)
        put("detail", receipt.detail)
    }

    private fun parse(value: String): JsonElement =
        if (value.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(value)

    private fun isFailed(value: JsonElement): Boolean =
        value is JsonObject && (value["success"] as? JsonPrimitive)?.content == "false"

    private fun hasGpuSamples(value: JsonElement): Boolean =
        value is JsonObject && (value["gpuTimings"] as? JsonObject)?.isNotEmpty() == true

    private fun noGpuSamples(caseId: String, metricsSeen: Boolean): JsonObject = buildJsonObject {
        put("success", false)
        put("error_code", "NO_GPU_SAMPLES")
        put("message", "GPU metrics did not return a non-empty gpuTimings object.")
        put("retryable", true)
        put("details", buildJsonObject {
            put("case_id", caseId)
            put("reason", if (metricsSeen) "empty_gpu_timings" else "missing_gpu_metrics_action")
        })
    }

    private fun incompleteProvenance(caseId: String): JsonObject = buildJsonObject {
        put("success", false)
        put("error_code", "INCOMPLETE_PROVENANCE")
        put("message", "Benchmark provenance did not prove the complete measured state.")
        put("retryable", false)
        put("details", buildJsonObject { put("case_id", caseId) })
    }

    private fun write(transaction: ArtifactManager.JobTransaction, name: String, value: String) {
        transaction.open(name).use { output -> output.write(value.toByteArray(StandardCharsets.UTF_8)) }
    }
}