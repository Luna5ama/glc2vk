package dev.vibris.core

import dev.vibris.protocol.v2.ServerMessage

internal class TerminalDelivery {
    fun send(
        session: ControlSession,
        message: ServerMessage,
    ) {
        session.send(message)
    }
}
