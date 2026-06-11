package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.BufferBinding
import dev.luna5ama.glc2vk.common.CaptureMetadata
import dev.luna5ama.glc2vk.common.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplayCommandNormalizationTest {
    @Test
    fun explicitDebugCommandsArePreservedAndBindingsInjected() {
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
            samplerBindings = emptyList(),
            imageBindings = emptyList(),
            storageBufferBindings = listOf(binding),
            uniformBufferBindings = emptyList(),
            commands = listOf(
                Command.PushDebugLabelCommand("outer"),
                Command.DispatchCommand(1, 2, 3),
                Command.PopDebugLabelCommand
            )
        )

        val replayCommands = metadata.commandsForReplay()

        assertEquals(3, replayCommands.size)
        assertIs<Command.PushDebugLabelCommand>(replayCommands[0]).also {
            assertEquals("outer", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[1]).also {
            assertEquals(listOf(binding), it.storageBufferBindings)
            assertEquals(emptyList(), it.debugLabels)
        }
        assertEquals(Command.PopDebugLabelCommand, replayCommands[2])
    }

    @Test
    fun legacyDebugLabelsExpandToSharedHierarchy() {
        val metadata = CaptureMetadata(
            images = emptyList(),
            buffers = emptyList(),
            samplerBindings = emptyList(),
            imageBindings = emptyList(),
            storageBufferBindings = emptyList(),
            uniformBufferBindings = emptyList(),
            commands = listOf(
                Command.DispatchCommand(1, 1, 1, debugLabels = listOf("outer")),
                Command.DispatchCommand(2, 1, 1, debugLabels = listOf("outer")),
                Command.DispatchCommand(3, 1, 1, debugLabels = listOf("outer", "inner")),
                Command.DispatchCommand(4, 1, 1, debugLabels = listOf("outer"))
            )
        )

        val replayCommands = metadata.commandsForReplay()

        assertEquals(8, replayCommands.size)
        assertIs<Command.PushDebugLabelCommand>(replayCommands[0]).also {
            assertEquals("outer", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[1]).also {
            assertEquals(1, it.x)
            assertEquals(emptyList(), it.debugLabels)
        }
        assertIs<Command.DispatchCommand>(replayCommands[2]).also {
            assertEquals(2, it.x)
            assertEquals(emptyList(), it.debugLabels)
        }
        assertIs<Command.PushDebugLabelCommand>(replayCommands[3]).also {
            assertEquals("inner", it.label)
        }
        assertIs<Command.DispatchCommand>(replayCommands[4]).also {
            assertEquals(3, it.x)
            assertEquals(emptyList(), it.debugLabels)
        }
        assertEquals(Command.PopDebugLabelCommand, replayCommands[5])
        assertIs<Command.DispatchCommand>(replayCommands[6]).also {
            assertEquals(4, it.x)
            assertEquals(emptyList(), it.debugLabels)
        }
        assertEquals(Command.PopDebugLabelCommand, replayCommands[7])
    }
}

