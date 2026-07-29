package dev.vibris.core

import dev.vibris.protocol.v1.JobCompleted
import dev.vibris.protocol.v1.JobFailed
import dev.vibris.protocol.v1.ProtocolVersion
import dev.vibris.protocol.v1.ServerMessage

@JvmRecord
internal data class TerminalResult(
    val completed: JobCompleted?,
    val failed: JobFailed?,
) {
    fun successful(): Boolean = completed != null

    fun message(messageId: String, requestId: String, workspaceId: String): ServerMessage {
        val result = ServerMessage.newBuilder()
            .setProtocolVersion(V1)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId)
        return if (successful()) {
            result.setJobCompleted(completed).build()
        } else {
            result.setJobFailed(failed).build()
        }
    }

    companion object {
        private val V1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build()

        @JvmStatic
        fun completed(value: JobCompleted): TerminalResult = TerminalResult(value, null)

        @JvmStatic
        fun failed(value: JobFailed): TerminalResult = TerminalResult(null, value)
    }
}