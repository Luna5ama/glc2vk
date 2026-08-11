package dev.vibris.core

import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.JobSpec
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@JvmRecord
internal data class GeneratedArtifact(
    val fileName: String,
    val kind: ArtifactKind,
    val format: ArtifactFormat,
    val mediaType: String,
)

internal object ProfileResultArtifacts {
    const val JSON_FILE = "result.json"
    const val CSV_FILE = "result.csv"

    fun requested(job: JobSpec): Boolean = job.hasResultArtifacts() &&
        (job.resultArtifacts.writeJson || job.resultArtifacts.writeCsv)

    fun write(
        job: JobSpec,
        transaction: ArtifactManager.JobTransaction,
        receipts: List<ActionReceipt>,
    ): List<GeneratedArtifact> {
        if (!requested(job)) return emptyList()
        val generated = ArrayList<GeneratedArtifact>()
        if (job.resultArtifacts.writeJson) {
            val document = buildJsonObject {
                put("schema_version", 2)
                put("job_id", job.jobId)
                put("action_receipts", buildJsonArray {
                    receipts.forEach { receipt ->
                        add(buildJsonObject {
                            put("action_index", receipt.actionIndex)
                            put("kind", receipt.kind.name.removePrefix("ACTION_KIND_").lowercase())
                            put("status", receipt.status.name.removePrefix("RECEIPT_STATUS_").lowercase())
                            if (receipt.hasError()) {
                                put("error", buildJsonObject {
                                    put("code", receipt.error.code.name.removePrefix("ERROR_CODE_").lowercase())
                                    put("message", receipt.error.message)
                                })
                            }
                        })
                    }
                })
            }
            transaction.open(JSON_FILE).use { output ->
                output.write(document.toString().toByteArray(StandardCharsets.UTF_8))
            }
            generated.add(
                GeneratedArtifact(
                    JSON_FILE,
                    ArtifactKind.ARTIFACT_KIND_RESULT,
                    ArtifactFormat.ARTIFACT_FORMAT_JSON,
                    "application/json",
                ),
            )
        }
        if (job.resultArtifacts.writeCsv) {
            val csv = buildString {
                append("action_index,kind,status\n")
                receipts.forEach { receipt ->
                    append(receipt.actionIndex).append(',')
                    append(receipt.kind.name.removePrefix("ACTION_KIND_").lowercase()).append(',')
                    append(receipt.status.name.removePrefix("RECEIPT_STATUS_").lowercase()).append('\n')
                }
            }
            transaction.open(CSV_FILE).use { output ->
                output.write(csv.toByteArray(StandardCharsets.UTF_8))
            }
            generated.add(
                GeneratedArtifact(
                    CSV_FILE,
                    ArtifactKind.ARTIFACT_KIND_RESULT,
                    ArtifactFormat.ARTIFACT_FORMAT_CSV,
                    "text/csv; charset=utf-8",
                ),
            )
        }
        return generated
    }
}
