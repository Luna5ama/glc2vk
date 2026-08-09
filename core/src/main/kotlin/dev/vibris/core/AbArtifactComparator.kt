package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.protocol.v1.AbComparisonResult
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

internal class AbArtifactComparator {
    @Throws(IOException::class)
    fun compare(
        transaction: ArtifactManager.JobTransaction,
        baseline: CapturePlan,
        candidate: CapturePlan,
        baselineLabel: String,
        candidateLabel: String,
    ): AbComparisonResult {
        val pairs = comparisonPairs(baseline.targets, candidate.targets)
        val heatmaps = heatmapFiles(baseline)
        val samples = ArrayList<Sample>()
        var pngIndex = 0
        for (pair in pairs) {
            val sample = if (pair.baseline.format == CapturePlan.ArtifactFormat.PNG) {
                imageSample(transaction, pair).also { sample ->
                    transaction.open(heatmaps[pngIndex++]).use { output ->
                        if (!ImageIO.write(sample.heatmap, "png", output)) {
                            throw IOException("PNG heatmap writer is unavailable.")
                        }
                    }
                }
            } else {
                binarySample(transaction, pair)
            }
            samples.add(sample)
        }
        val count = samples.sumOf { it.count }
        if (count == 0L) throw IOException("A/B capture groups contain no comparable samples.")
        val sum = samples.sumOf { it.sum }
        val sumSquares = samples.sumOf { it.sumSquares }
        val maximum = samples.maxOf { it.maximum }
        val mean = sum / count
        val rms = Math.sqrt(sumSquares / count)
        transaction.open(METRICS_FILE).use { output ->
            val json = String.format(
                Locale.ROOT,
                "{\"baseline\":\"%s\",\"candidate\":\"%s\"," +
                    "\"mean_absolute_error\":%.9f,\"root_mean_square_error\":%.9f," +
                    "\"max_absolute_error\":%.9f}",
                jsonString(baselineLabel), jsonString(candidateLabel), mean, rms, maximum,
            )
            output.write(json.toByteArray(StandardCharsets.UTF_8))
        }
        return AbComparisonResult.newBuilder()
            .setBaselineLabel(baselineLabel)
            .setCandidateLabel(candidateLabel)
            .setMeanAbsoluteError(mean)
            .setRootMeanSquareError(rms)
            .setMaxAbsoluteError(maximum)
            .build()
    }

    private fun comparisonPairs(
        baseline: List<CapturePlan.Target>,
        candidate: List<CapturePlan.Target>,
    ): List<Pair> {
        if (baseline.size != candidate.size || baseline.isEmpty()) throw IOException(TOPOLOGY_ERROR)
        val pairs = ArrayList<Pair>()
        for (index in baseline.indices) {
            val a = baseline[index]
            val b = candidate[index]
            if (a.kind != b.kind || a.logicalName != b.logicalName || a.format != b.format) {
                throw IOException(TOPOLOGY_ERROR)
            }
            val aPayloads = a.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA }
            val bPayloads = b.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA }
            if (aPayloads.size != bPayloads.size) throw IOException(TOPOLOGY_ERROR)
            for (outputIndex in aPayloads.indices) {
                val ao = aPayloads[outputIndex]
                val bo = bPayloads[outputIndex]
                if (ao.role != bo.role || ao.subresourceIndex != bo.subresourceIndex || ao.format != bo.format) {
                    throw IOException(TOPOLOGY_ERROR)
                }
                if (ao.format != CapturePlan.ArtifactFormat.PNG && ao.format != CapturePlan.ArtifactFormat.BIN) {
                    throw IOException("A/B comparison does not support ${ao.format} payloads.")
                }
                pairs.add(Pair(ao, bo))
            }
        }
        return pairs
    }

    private fun imageSample(transaction: ArtifactManager.JobTransaction, pair: Pair): Sample {
        val baseline = ImageIO.read(transaction.readableArtifact(pair.baseline.fileName).toFile())
        val candidate = ImageIO.read(transaction.readableArtifact(pair.candidate.fileName).toFile())
        if (baseline == null || candidate == null || baseline.width != candidate.width ||
            baseline.height != candidate.height || baseline.raster.numBands != candidate.raster.numBands
        ) throw IOException("A/B PNG format or dimensions do not match.")
        val bits = baseline.sampleModel.sampleSize
        if (!bits.contentEquals(candidate.sampleModel.sampleSize)) {
            throw IOException("A/B PNG sample bit depths do not match.")
        }
        val heatmap = BufferedImage(baseline.width, baseline.height, BufferedImage.TYPE_INT_ARGB)
        var sum = 0.0
        var squares = 0.0
        var maximum = 0.0
        var count = 0L
        for (y in 0 until baseline.height) for (x in 0 until baseline.width) {
            var pixelMaximum = 0.0
            for (band in 0 until baseline.raster.numBands) {
                val scale = ((1L shl bits[band].coerceAtMost(30)) - 1L).toDouble()
                val difference = abs(
                    baseline.raster.getSample(x, y, band) - candidate.raster.getSample(x, y, band),
                ) / scale
                sum += difference
                squares += difference * difference
                maximum = maxOf(maximum, difference)
                pixelMaximum = maxOf(pixelMaximum, difference)
                count++
            }
            heatmap.setRGB(x, y, 0xff000000.toInt() or ((pixelMaximum * 255.0).toInt().coerceIn(0, 255) shl 16))
        }
        return Sample(count, sum, squares, maximum, heatmap)
    }

    private fun binarySample(transaction: ArtifactManager.JobTransaction, pair: Pair): Sample {
        val baseline = Files.readAllBytes(transaction.readableArtifact(pair.baseline.fileName))
        val candidate = Files.readAllBytes(transaction.readableArtifact(pair.candidate.fileName))
        if (baseline.size != candidate.size || baseline.isEmpty()) {
            throw IOException("A/B BIN artifact sizes do not match.")
        }
        var sum = 0.0
        var squares = 0.0
        var maximum = 0.0
        for (index in baseline.indices) {
            val difference = abs((baseline[index].toInt() and 0xff) - (candidate[index].toInt() and 0xff)) / 255.0
            sum += difference
            squares += difference * difference
            maximum = maxOf(maximum, difference)
        }
        return Sample(baseline.size.toLong(), sum, squares, maximum,
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    }

    private fun jsonString(value: String): String = buildString(value.length) {
        for (character in value) when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < '\u0020') append(String.format(Locale.ROOT, "\\u%04x", character.code))
            else append(character)
        }
    }

    private data class Pair(
        val baseline: CapturePlan.ArtifactOutputSpec,
        val candidate: CapturePlan.ArtifactOutputSpec,
    )

    private data class Sample(
        val count: Long,
        val sum: Double,
        val sumSquares: Double,
        val maximum: Double,
        val heatmap: BufferedImage,
    )

    companion object {
        const val METRICS_FILE = "diff.json"
        const val HEATMAP_FILE = "diff-heatmap.png"
        private const val TOPOLOGY_ERROR = "A/B artifact group topology does not match."

        fun heatmapFiles(plan: CapturePlan): List<String> {
            val png = plan.targets.flatMap { target ->
                target.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA &&
                    it.format == CapturePlan.ArtifactFormat.PNG }.map { target to it }
            }
            return png.map { (target, output) ->
                when {
                    output.subresourceIndex != null -> {
                        val depth = target.outputs.count { it.role == CapturePlan.ArtifactRole.SUBRESOURCE }
                        val digits = maxOf(4, (depth - 1).coerceAtLeast(0).toString().length)
                        "diff-heatmap.layer${output.subresourceIndex.toString().padStart(digits, '0')}.png"
                    }
                    png.size == 1 -> HEATMAP_FILE
                    else -> "diff-heatmap.${target.artifactName}.png"
                }
            }.also { names ->
                if (names.size != names.toSet().size) throw IOException(TOPOLOGY_ERROR)
            }
        }
    }
}
