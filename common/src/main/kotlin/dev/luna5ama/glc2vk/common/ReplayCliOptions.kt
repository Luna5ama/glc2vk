package dev.luna5ama.glc2vk.common

import java.nio.file.Path
import kotlin.io.path.Path

data class ReplayCliOptions(
    val capturePath: Path,
    val frameLimit: Long?,
    val shaderPath: Path?
)

fun parseReplayCliOptions(args: Array<String>): ReplayCliOptions {
    check(args.isNotEmpty()) { "Expected at least 1 argument: <path to capture>" }

    var frameLimit: Long? = null
    var shaderPath: Path? = null
    var index = 1
    while (index < args.size) {
        when (val arg = args[index]) {
            "--shader-path", "--shader-root" -> {
                check(index + 1 < args.size) { "$arg requires a path argument" }
                shaderPath = Path(args[index + 1])
                index += 2
            }

            else -> {
                val value = arg.toLongOrNull()
                    ?: error("Unknown replay argument: $arg")
                check(frameLimit == null) { "Replay frame/exit limit specified more than once" }
                frameLimit = value
                index += 1
            }
        }
    }

    return ReplayCliOptions(
        capturePath = Path(args[0]),
        frameLimit = frameLimit,
        shaderPath = shaderPath
    )
}
