package dev.vibris.protocol

import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ProtocolVersion

object ProtocolVersionGate {
    const val MAJOR: Int = 2
    const val MINOR: Int = 0
    const val ERROR_CODE: String = "UNSUPPORTED_VERSION"

    @JvmStatic
    fun current(): ProtocolVersion = ProtocolVersion.newBuilder()
        .setMajor(MAJOR)
        .setMinor(MINOR)
        .build()

    @JvmStatic
    fun requireSupported(version: ProtocolVersion?) {
        if (version == null || version.major != MAJOR) {
            throw UnsupportedProtocolVersionException(version?.major)
        }
    }

    @JvmStatic
    fun requireSupported(message: ClientMessage) {
        if (!message.hasProtocolVersion()) {
            throw UnsupportedProtocolVersionException(null)
        }
        requireSupported(message.protocolVersion)
    }
}

class UnsupportedProtocolVersionException internal constructor(val suppliedMajor: Int?) :
    IllegalArgumentException(
        "${ProtocolVersionGate.ERROR_CODE}: expected protocol major ${ProtocolVersionGate.MAJOR}, " +
            "received ${suppliedMajor ?: "missing"}",
    )