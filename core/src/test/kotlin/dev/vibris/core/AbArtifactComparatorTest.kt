package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.VisualThresholds
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AbArtifactComparatorTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun reportsCompletePngMetricsAndFailsConfiguredGate() {
        val baseline = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
        val candidate = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, Color.RED.rgb)
        }
        val thresholds = VisualThresholds.newBuilder()
            .setPixelErrorThreshold(0.1)
            .setMaxMeanAbsoluteError(0.1)
            .setMaxRootMeanSquareError(0.2)
            .setMaxP95AbsoluteError(0.9)
            .setMaxAbsoluteError(0.9)
            .setMaxThresholdPixelRatio(0.25)
            .setMinSsim(0.99)
            .build()

        val result = compare("failed-gate", baseline, candidate, thresholds)

        assertEquals(6, result.sampleCount)
        assertEquals(2, result.pixelCount)
        assertEquals(1.0 / 6.0, result.meanAbsoluteError, 1e-9)
        assertEquals(kotlin.math.sqrt(1.0 / 6.0), result.rootMeanSquareError, 1e-9)
        assertEquals(1.0, result.p95AbsoluteError, 1e-9)
        assertEquals(1.0, result.maxAbsoluteError, 1e-9)
        assertEquals(0.5, result.thresholdPixelRatio, 1e-9)
        assertTrue(result.hasSsim())
        assertTrue(result.ssim < 0.99)
        assertEquals("failed", result.verdict)
        assertEquals(
            listOf(
                "MAE_EXCEEDED",
                "RMSE_EXCEEDED",
                "P95_ERROR_EXCEEDED",
                "MAX_ERROR_EXCEEDED",
                "THRESHOLD_PIXEL_RATIO_EXCEEDED",
                "SSIM_BELOW_MINIMUM",
            ),
            result.violationsList,
        )
        assertTrue(Files.isRegularFile(temp.resolve("failed-gate/diff.json")))
        assertTrue(Files.isRegularFile(temp.resolve("failed-gate/diff-heatmap.png")))
        val metrics = Files.readString(temp.resolve("failed-gate/diff.json"))
        assertTrue(metrics.contains("\"p95_absolute_error\":1.000000000"))
        assertTrue(metrics.contains("\"max_threshold_pixel_ratio\":0.250000000"))
        assertTrue(metrics.contains("\"verdict\":\"failed\""))
    }

    @Test
    fun identicalPngPassesAndReportsPerfectSsim() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, Color(32, 64, 128).rgb)
            setRGB(1, 0, Color(255, 128, 0).rgb)
        }
        val thresholds = VisualThresholds.newBuilder()
            .setPixelErrorThreshold(0.0)
            .setMaxMeanAbsoluteError(0.0)
            .setMaxThresholdPixelRatio(0.0)
            .setMinSsim(1.0)
            .build()

        val result = compare("passed-gate", image, image, thresholds)

        assertTrue(result.passed)
        assertEquals("passed", result.verdict)
        assertEquals(0.0, result.p95AbsoluteError)
        assertEquals(0.0, result.thresholdPixelRatio)
        assertEquals(1.0, result.ssim, 1e-9)
        assertTrue(result.violationsList.isEmpty())
    }

    @Test
    fun comparisonWithoutThresholdsReportsButDoesNotGate() {
        val baseline = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val candidate = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, Color.WHITE.rgb)
        }

        val result = compare("report-only", baseline, candidate, null)

        assertTrue(result.passed)
        assertEquals("not_evaluated", result.verdict)
        assertEquals(1.0, result.maxAbsoluteError)
        assertTrue(!result.hasThresholds())
    }

    private fun compare(
        request: String,
        baseline: BufferedImage,
        candidate: BufferedImage,
        thresholds: VisualThresholds?,
    ) = ArtifactManager(temp.resolve("artifacts-$request"), 1024 * 1024).beginJob(
        WORKSPACE_ID,
        request,
        0,
    ).use { transaction ->
        transaction.open("baseline.png").use { ImageIO.write(baseline, "png", it) }
        transaction.open("candidate.png").use { ImageIO.write(candidate, "png", it) }
        val result = AbArtifactComparator().compare(
            transaction,
            plan("baseline", "baseline.png"),
            plan("candidate", "candidate.png"),
            "baseline",
            "candidate",
            thresholds,
        )
        val committed = transaction.commit(
            setOf("baseline.png", "candidate.png", "diff.json", "diff-heatmap.png"),
        )
        val output = temp.resolve(request)
        Files.createDirectory(output)
        committed.artifacts().forEach { (name, path) -> Files.copy(path, output.resolve(name)) }
        result
    }

    private fun plan(artifactName: String, fileName: String) = CapturePlan(
        listOf(
            CapturePlan.Target(
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
                "final",
                CapturePlan.ArtifactFormat.PNG,
                artifactName,
                0,
                0,
                listOf(
                    CapturePlan.ArtifactOutputSpec(
                        fileName,
                        CapturePlan.ArtifactFormat.PNG,
                        CapturePlan.ArtifactRole.PRIMARY,
                        null,
                    ),
                ),
            ),
        ),
    )

    companion object {
        private const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
