package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.protocol.v2.CompareReceipt
import dev.vibris.protocol.v2.VisualMetrics
import dev.vibris.protocol.v2.VisualThresholds
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil

internal class AbArtifactComparator {
    @Throws(IOException::class)
    fun compare(
        transaction: ArtifactManager.JobTransaction,
        baseline: CapturePlan,
        candidate: CapturePlan,
        baselineLabel: String,
        candidateLabel: String,
        thresholds: VisualThresholds?,
    ): CompareReceipt {
        val pairs = comparisonPairs(baseline.targets, candidate.targets)
        val heatmaps = heatmapFiles(baseline)
        val samples = ArrayList<Sample>()
        val pixelThreshold = thresholds?.pixelErrorThreshold ?: 0.0
        var pngIndex = 0
        for (pair in pairs) {
            val sample = if (pair.baseline.format == CapturePlan.ArtifactFormat.PNG) {
                imageSample(transaction, pair, pixelThreshold).also { sample ->
                    transaction.open(heatmaps[pngIndex++]).use { output ->
                        if (!ImageIO.write(sample.heatmap, "png", output)) {
                            throw IOException("PNG heatmap writer is unavailable.")
                        }
                    }
                }
            } else {
                binarySample(transaction, pair, pixelThreshold)
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
        val histogram = LongArray(HISTOGRAM_BUCKETS)
        samples.forEach { sample ->
            sample.histogram.forEachIndexed { index, value -> histogram[index] += value }
        }
        val p95 = percentile(histogram, count, 0.95)
        val pixelCount = samples.sumOf { it.pixelCount }
        val pixelsAboveThreshold = samples.sumOf { it.pixelsAboveThreshold }
        val thresholdPixelRatio = pixelsAboveThreshold.toDouble() / pixelCount
        val ssim = ssim(samples)
        val violations = violations(thresholds, mean, rms, p95, maximum, thresholdPixelRatio, ssim)
        val passed = violations.isEmpty()
        val verdict = when {
            thresholds == null -> "not_evaluated"
            passed -> "passed"
            else -> "failed"
        }
        transaction.open(METRICS_FILE).use { output ->
            val json = metricsJson(
                baselineLabel, candidateLabel, mean, rms, p95, maximum, thresholdPixelRatio,
                ssim, count, pixelCount, pixelThreshold, thresholds, passed, verdict, violations,
            )
            output.write(json.toByteArray(StandardCharsets.UTF_8))
        }
        val metrics = VisualMetrics.newBuilder()
            .setMeanAbsoluteError(mean)
            .setRootMeanSquareError(rms)
            .setMaxAbsoluteError(maximum)
            .setP95AbsoluteError(p95)
            .setThresholdPixelRatio(thresholdPixelRatio)
            .setSampleCount(count)
            .setPixelCount(pixelCount)
            .apply { ssim?.let { setSsim(it) } }
        return CompareReceipt.newBuilder()
            .setMetrics(metrics)
            .setPassed(passed)
            .addAllViolations(violations)
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

    private fun imageSample(
        transaction: ArtifactManager.JobTransaction,
        pair: Pair,
        pixelThreshold: Double,
    ): Sample {
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
        var pixelCount = 0L
        var pixelsAboveThreshold = 0L
        val histogram = LongArray(HISTOGRAM_BUCKETS)
        val ssim = SsimMoments()
        for (y in 0 until baseline.height) for (x in 0 until baseline.width) {
            var pixelMaximum = 0.0
            for (band in 0 until baseline.raster.numBands) {
                val scale = sampleScale(bits[band])
                val difference = abs(
                    baseline.raster.getSample(x, y, band) - candidate.raster.getSample(x, y, band),
                ) / scale
                sum += difference
                squares += difference * difference
                maximum = maxOf(maximum, difference)
                pixelMaximum = maxOf(pixelMaximum, difference)
                histogram[bucket(difference)]++
                count++
            }
            pixelCount++
            if (pixelMaximum > pixelThreshold) pixelsAboveThreshold++
            ssim.add(luminance(baseline, bits, x, y), luminance(candidate, bits, x, y))
            heatmap.setRGB(x, y, 0xff000000.toInt() or ((pixelMaximum * 255.0).toInt().coerceIn(0, 255) shl 16))
        }
        return Sample(count, sum, squares, maximum, pixelCount, pixelsAboveThreshold, histogram, ssim, heatmap)
    }

    private fun binarySample(
        transaction: ArtifactManager.JobTransaction,
        pair: Pair,
        pixelThreshold: Double,
    ): Sample {
        val baseline = Files.readAllBytes(transaction.readableArtifact(pair.baseline.fileName))
        val candidate = Files.readAllBytes(transaction.readableArtifact(pair.candidate.fileName))
        if (baseline.size != candidate.size || baseline.isEmpty()) {
            throw IOException("A/B BIN artifact sizes do not match.")
        }
        var sum = 0.0
        var squares = 0.0
        var maximum = 0.0
        var aboveThreshold = 0L
        val histogram = LongArray(HISTOGRAM_BUCKETS)
        for (index in baseline.indices) {
            val difference = abs((baseline[index].toInt() and 0xff) - (candidate[index].toInt() and 0xff)) / 255.0
            sum += difference
            squares += difference * difference
            maximum = maxOf(maximum, difference)
            histogram[bucket(difference)]++
            if (difference > pixelThreshold) aboveThreshold++
        }
        return Sample(
            baseline.size.toLong(), sum, squares, maximum, baseline.size.toLong(), aboveThreshold,
            histogram, SsimMoments(), BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        )
    }

    private fun luminance(image: BufferedImage, bits: IntArray, x: Int, y: Int): Double =
        if (image.raster.numBands >= 3) {
            0.2126 * image.raster.getSample(x, y, 0) / sampleScale(bits[0]) +
                0.7152 * image.raster.getSample(x, y, 1) / sampleScale(bits[1]) +
                0.0722 * image.raster.getSample(x, y, 2) / sampleScale(bits[2])
        } else {
            image.raster.getSample(x, y, 0) / sampleScale(bits[0])
        }

    private fun ssim(samples: List<Sample>): Double? {
        val moments = SsimMoments()
        samples.forEach { moments.add(it.ssim) }
        if (moments.count == 0L) return null
        val meanA = moments.sumA / moments.count
        val meanB = moments.sumB / moments.count
        val varianceA = maxOf(0.0, moments.sumSquaresA / moments.count - meanA * meanA)
        val varianceB = maxOf(0.0, moments.sumSquaresB / moments.count - meanB * meanB)
        val covariance = moments.sumProducts / moments.count - meanA * meanB
        val c1 = 0.01 * 0.01
        val c2 = 0.03 * 0.03
        return (((2.0 * meanA * meanB + c1) * (2.0 * covariance + c2)) /
            ((meanA * meanA + meanB * meanB + c1) * (varianceA + varianceB + c2))).coerceIn(-1.0, 1.0)
    }

    private fun violations(
        thresholds: VisualThresholds?,
        mean: Double,
        rms: Double,
        p95: Double,
        maximum: Double,
        thresholdPixelRatio: Double,
        ssim: Double?,
    ): List<String> {
        if (thresholds == null) return emptyList()
        val result = ArrayList<String>()
        if (thresholds.hasMaxMeanAbsoluteError() && mean > thresholds.maxMeanAbsoluteError) {
            result.add("MAE_EXCEEDED")
        }
        if (thresholds.hasMaxRootMeanSquareError() && rms > thresholds.maxRootMeanSquareError) {
            result.add("RMSE_EXCEEDED")
        }
        if (thresholds.hasMaxP95AbsoluteError() && p95 > thresholds.maxP95AbsoluteError) {
            result.add("P95_ERROR_EXCEEDED")
        }
        if (thresholds.hasMaxAbsoluteError() && maximum > thresholds.maxAbsoluteError) {
            result.add("MAX_ERROR_EXCEEDED")
        }
        if (thresholds.hasMaxThresholdPixelRatio() &&
            thresholdPixelRatio > thresholds.maxThresholdPixelRatio
        ) {
            result.add("THRESHOLD_PIXEL_RATIO_EXCEEDED")
        }
        if (thresholds.hasMinSsim()) {
            if (ssim == null) result.add("SSIM_UNAVAILABLE")
            else if (ssim < thresholds.minSsim) result.add("SSIM_BELOW_MINIMUM")
        }
        return result
    }

    private fun metricsJson(
        baselineLabel: String,
        candidateLabel: String,
        mean: Double,
        rms: Double,
        p95: Double,
        maximum: Double,
        thresholdPixelRatio: Double,
        ssim: Double?,
        count: Long,
        pixelCount: Long,
        pixelThreshold: Double,
        thresholds: VisualThresholds?,
        passed: Boolean,
        verdict: String,
        violations: List<String>,
    ): String = buildString {
        append("{\"baseline\":\"").append(jsonString(baselineLabel))
        append("\",\"candidate\":\"").append(jsonString(candidateLabel)).append("\"")
        append(",\"mean_absolute_error\":").append(number(mean))
        append(",\"root_mean_square_error\":").append(number(rms))
        append(",\"p95_absolute_error\":").append(number(p95))
        append(",\"max_absolute_error\":").append(number(maximum))
        append(",\"threshold_pixel_ratio\":").append(number(thresholdPixelRatio))
        append(",\"ssim\":").append(ssim?.let(::number) ?: "null")
        append(",\"sample_count\":").append(count)
        append(",\"pixel_count\":").append(pixelCount)
        append(",\"pixel_error_threshold\":").append(number(pixelThreshold))
        append(",\"thresholds_configured\":").append(thresholds != null)
        append(",\"thresholds\":")
        if (thresholds == null) {
            append("null")
        } else {
            append("{\"pixel_error_threshold\":").append(number(thresholds.pixelErrorThreshold))
            fun optional(name: String, present: Boolean, value: Double) {
                if (present) append(",\"").append(name).append("\":").append(number(value))
            }
            optional("max_mean_absolute_error", thresholds.hasMaxMeanAbsoluteError(), thresholds.maxMeanAbsoluteError)
            optional(
                "max_root_mean_square_error",
                thresholds.hasMaxRootMeanSquareError(),
                thresholds.maxRootMeanSquareError,
            )
            optional("max_p95_absolute_error", thresholds.hasMaxP95AbsoluteError(), thresholds.maxP95AbsoluteError)
            optional("max_absolute_error", thresholds.hasMaxAbsoluteError(), thresholds.maxAbsoluteError)
            optional(
                "max_threshold_pixel_ratio",
                thresholds.hasMaxThresholdPixelRatio(),
                thresholds.maxThresholdPixelRatio,
            )
            optional("min_ssim", thresholds.hasMinSsim(), thresholds.minSsim)
            append('}')
        }
        append(",\"passed\":").append(passed)
        append(",\"verdict\":\"").append(verdict).append("\"")
        append(",\"violations\":[")
        violations.forEachIndexed { index, value ->
            if (index != 0) append(',')
            append('\"').append(value).append('\"')
        }
        append("]}")
    }

    private fun percentile(histogram: LongArray, count: Long, percentile: Double): Double {
        val target = ceil(count * percentile).toLong().coerceAtLeast(1L)
        var observed = 0L
        for (index in histogram.indices) {
            observed += histogram[index]
            if (observed >= target) return index.toDouble() / (HISTOGRAM_BUCKETS - 1)
        }
        return 1.0
    }

    private fun bucket(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * (HISTOGRAM_BUCKETS - 1)).toInt()

    private fun sampleScale(bits: Int): Double = ((1L shl bits.coerceAtMost(30)) - 1L).toDouble()

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.9f", value)

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
        val pixelCount: Long,
        val pixelsAboveThreshold: Long,
        val histogram: LongArray,
        val ssim: SsimMoments,
        val heatmap: BufferedImage,
    )

    private data class SsimMoments(
        var count: Long = 0,
        var sumA: Double = 0.0,
        var sumB: Double = 0.0,
        var sumSquaresA: Double = 0.0,
        var sumSquaresB: Double = 0.0,
        var sumProducts: Double = 0.0,
    ) {
        fun add(a: Double, b: Double) {
            count++
            sumA += a
            sumB += b
            sumSquaresA += a * a
            sumSquaresB += b * b
            sumProducts += a * b
        }

        fun add(other: SsimMoments) {
            count += other.count
            sumA += other.sumA
            sumB += other.sumB
            sumSquaresA += other.sumSquaresA
            sumSquaresB += other.sumSquaresB
            sumProducts += other.sumProducts
        }
    }

    companion object {
        const val METRICS_FILE = "diff.json"
        const val HEATMAP_FILE = "diff-heatmap.png"
        private const val HISTOGRAM_BUCKETS = 65_536
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
