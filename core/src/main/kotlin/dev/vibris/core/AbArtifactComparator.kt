package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.protocol.v1.AbComparisonResult
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO

internal class AbArtifactComparator {
    @Throws(IOException::class)
    fun compare(
        transaction: ArtifactManager.JobTransaction,
        baseline: CapturePlan,
        candidate: CapturePlan,
        baselineLabel: String,
        candidateLabel: String,
    ): AbComparisonResult {
        val pair = comparisonPair(baseline.targets(), candidate.targets())
        val sample = if (pair.baseline.format == CapturePlan.ArtifactFormat.PNG) {
            imageSample(transaction, pair)
        } else {
            binarySample(transaction, pair)
        }
        val mean = sample.sum / sample.count / 255.0
        val rms = Math.sqrt(sample.sumSquares / sample.count) / 255.0
        val maximum = sample.maximum / 255.0
        transaction.open(METRICS_FILE).use { output ->
            val json = String.format(
                Locale.ROOT,
                "{\"baseline\":\"%s\",\"candidate\":\"%s\"," +
                    "\"mean_absolute_error\":%.9f,\"root_mean_square_error\":%.9f," +
                    "\"max_absolute_error\":%.9f}",
                jsonString(baselineLabel),
                jsonString(candidateLabel),
                mean,
                rms,
                maximum,
            )
            output.write(json.toByteArray(StandardCharsets.UTF_8))
        }
        transaction.open(HEATMAP_FILE).use { output ->
            if (!ImageIO.write(sample.heatmap, "png", output)) {
                throw IOException("PNG heatmap writer is unavailable.")
            }
        }
        return AbComparisonResult.newBuilder()
            .setBaselineLabel(baselineLabel)
            .setCandidateLabel(candidateLabel)
            .setMeanAbsoluteError(mean)
            .setRootMeanSquareError(rms)
            .setMaxAbsoluteError(maximum)
            .build()
    }

    private fun comparisonPair(
        baseline: List<CapturePlan.Target>,
        candidate: List<CapturePlan.Target>,
    ): Pair {
        if (baseline.size != candidate.size || baseline.isEmpty()) {
            throw IOException("A/B capture plans do not match.")
        }
        for (index in baseline.indices) {
            val a = baseline[index]
            val b = candidate[index]
            if (a.kind != b.kind || a.format != b.format || a.logicalName != b.logicalName) {
                throw IOException("A/B capture plans do not match.")
            }
            if (a.format == CapturePlan.ArtifactFormat.PNG) {
                return Pair(a, b)
            }
        }
        return Pair(baseline.first(), candidate.first())
    }

    private fun imageSample(transaction: ArtifactManager.JobTransaction, pair: Pair): Sample {
        val baseline = ImageIO.read(transaction.readableArtifact(pair.baseline.fileName()).toFile())
        val candidate = ImageIO.read(transaction.readableArtifact(pair.candidate.fileName()).toFile())
        if (
            baseline == null ||
            candidate == null ||
            baseline.width != candidate.width ||
            baseline.height != candidate.height
        ) {
            throw IOException("A/B PNG dimensions do not match.")
        }
        val heatmap = BufferedImage(baseline.width, baseline.height, BufferedImage.TYPE_INT_ARGB)
        var sum = 0.0
        var squares = 0.0
        var maximum = 0
        var count = 0L
        for (y in 0 until baseline.height) {
            for (x in 0 until baseline.width) {
                val a = baseline.getRGB(x, y)
                val b = candidate.getRGB(x, y)
                var pixelMaximum = 0
                for (shift in intArrayOf(0, 8, 16, 24)) {
                    val difference = Math.abs((a ushr shift and 0xff) - (b ushr shift and 0xff))
                    sum += difference
                    squares += difference.toDouble() * difference
                    maximum = maxOf(maximum, difference)
                    pixelMaximum = maxOf(pixelMaximum, difference)
                    count++
                }
                heatmap.setRGB(x, y, 0xff000000.toInt() or (pixelMaximum shl 16))
            }
        }
        return Sample(count, sum, squares, maximum, heatmap)
    }

    private fun binarySample(transaction: ArtifactManager.JobTransaction, pair: Pair): Sample {
        val baseline = Files.readAllBytes(transaction.readableArtifact(pair.baseline.fileName()))
        val candidate = Files.readAllBytes(transaction.readableArtifact(pair.candidate.fileName()))
        if (baseline.size != candidate.size || baseline.isEmpty()) {
            throw IOException("A/B binary artifact sizes do not match.")
        }
        val width = minOf(1024, baseline.size)
        val height = Math.toIntExact((baseline.size.toLong() + width - 1) / width)
        val heatmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var sum = 0.0
        var squares = 0.0
        var maximum = 0
        for (index in baseline.indices) {
            val difference = Math.abs(
                (baseline[index].toInt() and 0xff) - (candidate[index].toInt() and 0xff),
            )
            sum += difference
            squares += difference.toDouble() * difference
            maximum = maxOf(maximum, difference)
            heatmap.setRGB(index % width, index / width, 0xff000000.toInt() or (difference shl 16))
        }
        return Sample(baseline.size.toLong(), sum, squares, maximum, heatmap)
    }

    private fun jsonString(value: String): String {
        val escaped = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\b' -> escaped.append("\\b")
                '\u000c' -> escaped.append("\\f")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> {
                    if (character < '\u0020') {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                    } else {
                        escaped.append(character)
                    }
                }
            }
        }
        return escaped.toString()
    }

    private data class Pair(
        val baseline: CapturePlan.Target,
        val candidate: CapturePlan.Target,
    )

    private data class Sample(
        val count: Long,
        val sum: Double,
        val sumSquares: Double,
        val maximum: Int,
        val heatmap: BufferedImage,
    )

    companion object {
        const val METRICS_FILE = "diff.json"
        const val HEATMAP_FILE = "diff-heatmap.png"
    }
}