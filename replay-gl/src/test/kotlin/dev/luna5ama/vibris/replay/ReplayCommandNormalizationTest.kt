package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.BufferBinding
import dev.luna5ama.vibris.common.CaptureMetadata
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.PassInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplayCommandNormalizationTest {
    @Test
    fun explicitDebugCommandsArePreservedAndBindingsComeFromPassInfo() {
        val binding = BufferBinding(
            name = "Data",
            bufferIndex = 0,
            set = 2,
            binding = 0,
            offset = 4L
        )
        val metadata = CaptureMetadata(
            images = emptyList(),
            buffers = emptyList(),
            commands = listOf(
                Command.PushDebugLabelCommand("outer"),
                Command.DispatchCommand(
                    1,
                    2,
                    3,
                    passInfo = PassInfo(storageBufferBindings = listOf(binding))
                ),
                Command.PopDebugLabelCommand
            )
        )

        val replayCommands = metadata.commandsForReplay()

        assertEquals(3, replayCommands.size)
        assertEquals(listOf(binding), metadata.allStorageBufferBindings())
        assertIs<Command.PushDebugLabelCommand>(replayCommands[0]).also {
            assertEquals("outer", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[1]).also {
            assertEquals(listOf(binding), it.passInfo.storageBufferBindings)
        }
        assertEquals(Command.PopDebugLabelCommand, replayCommands[2])
    }

    @Test
    fun unclosedDebugLabelsAreClosedForReplay() {
        val metadata = CaptureMetadata(
            images = emptyList(),
            buffers = emptyList(),
            commands = listOf(
                Command.PushDebugLabelCommand("outer"),
                Command.DispatchCommand(1, 1, 1),
                Command.PushDebugLabelCommand("inner"),
                Command.DispatchCommand(2, 1, 1)
            )
        )

        val replayCommands = metadata.commandsForReplay()

        assertEquals(6, replayCommands.size)
        assertIs<Command.PushDebugLabelCommand>(replayCommands[0]).also {
            assertEquals("outer", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[1]).also {
            assertEquals(1, it.x)
        }
        assertIs<Command.PushDebugLabelCommand>(replayCommands[2]).also {
            assertEquals("inner", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[3]).also {
            assertEquals(2, it.x)
        }
        assertEquals(Command.PopDebugLabelCommand, replayCommands[4])
        assertEquals(Command.PopDebugLabelCommand, replayCommands[5])
    }
}

