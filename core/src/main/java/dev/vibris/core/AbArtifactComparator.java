package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.protocol.v1.AbComparisonResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

final class AbArtifactComparator {
    static final String METRICS_FILE = "diff.json";
    static final String HEATMAP_FILE = "diff-heatmap.png";

    AbComparisonResult compare(
        ArtifactManager.JobTransaction transaction,
        CapturePlan baseline,
        CapturePlan candidate,
        String baselineLabel,
        String candidateLabel
    ) throws IOException {
        Pair pair = comparisonPair(baseline.targets(), candidate.targets());
        Sample sample = pair.baseline.format() == CapturePlan.ArtifactFormat.PNG
            ? imageSample(transaction, pair)
            : binarySample(transaction, pair);
        double mean = sample.sum / sample.count / 255.0;
        double rms = Math.sqrt(sample.sumSquares / sample.count) / 255.0;
        double maximum = sample.maximum / 255.0;
        try (OutputStream output = transaction.open(METRICS_FILE)) {
            String json = String.format(Locale.ROOT,
                "{\"baseline\":\"%s\",\"candidate\":\"%s\","
                    + "\"mean_absolute_error\":%.9f,\"root_mean_square_error\":%.9f,"
                    + "\"max_absolute_error\":%.9f}",
                jsonString(baselineLabel), jsonString(candidateLabel), mean, rms, maximum);
            output.write(json.getBytes(StandardCharsets.UTF_8));
        }
        try (OutputStream output = transaction.open(HEATMAP_FILE)) {
            if (!ImageIO.write(sample.heatmap, "png", output)) {
                throw new IOException("PNG heatmap writer is unavailable.");
            }
        }
        return AbComparisonResult.newBuilder()
            .setBaselineLabel(baselineLabel)
            .setCandidateLabel(candidateLabel)
            .setMeanAbsoluteError(mean)
            .setRootMeanSquareError(rms)
            .setMaxAbsoluteError(maximum)
            .build();
    }

    private static Pair comparisonPair(
        List<CapturePlan.Target> baseline,
        List<CapturePlan.Target> candidate
    ) throws IOException {
        if (baseline.size() != candidate.size() || baseline.isEmpty()) {
            throw new IOException("A/B capture plans do not match.");
        }
        for (int index = 0; index < baseline.size(); index++) {
            CapturePlan.Target a = baseline.get(index);
            CapturePlan.Target b = candidate.get(index);
            if (a.kind() != b.kind() || a.format() != b.format() || !a.logicalName().equals(b.logicalName())) {
                throw new IOException("A/B capture plans do not match.");
            }
            if (a.format() == CapturePlan.ArtifactFormat.PNG) return new Pair(a, b);
        }
        return new Pair(baseline.getFirst(), candidate.getFirst());
    }

    private static Sample imageSample(ArtifactManager.JobTransaction transaction, Pair pair) throws IOException {
        BufferedImage baseline = ImageIO.read(transaction.readableArtifact(pair.baseline.fileName()).toFile());
        BufferedImage candidate = ImageIO.read(transaction.readableArtifact(pair.candidate.fileName()).toFile());
        if (baseline == null || candidate == null || baseline.getWidth() != candidate.getWidth() ||
            baseline.getHeight() != candidate.getHeight()) {
            throw new IOException("A/B PNG dimensions do not match.");
        }
        BufferedImage heatmap = new BufferedImage(
            baseline.getWidth(), baseline.getHeight(), BufferedImage.TYPE_INT_ARGB);
        double sum = 0;
        double squares = 0;
        int maximum = 0;
        long count = 0;
        for (int y = 0; y < baseline.getHeight(); y++) {
            for (int x = 0; x < baseline.getWidth(); x++) {
                int a = baseline.getRGB(x, y);
                int b = candidate.getRGB(x, y);
                int pixelMaximum = 0;
                for (int shift : new int[]{0, 8, 16, 24}) {
                    int difference = Math.abs((a >>> shift & 0xff) - (b >>> shift & 0xff));
                    sum += difference;
                    squares += (double) difference * difference;
                    maximum = Math.max(maximum, difference);
                    pixelMaximum = Math.max(pixelMaximum, difference);
                    count++;
                }
                heatmap.setRGB(x, y, 0xff000000 | pixelMaximum << 16);
            }
        }
        return new Sample(count, sum, squares, maximum, heatmap);
    }

    private static Sample binarySample(ArtifactManager.JobTransaction transaction, Pair pair) throws IOException {
        byte[] baseline = Files.readAllBytes(transaction.readableArtifact(pair.baseline.fileName()));
        byte[] candidate = Files.readAllBytes(transaction.readableArtifact(pair.candidate.fileName()));
        if (baseline.length != candidate.length || baseline.length == 0) {
            throw new IOException("A/B binary artifact sizes do not match.");
        }
        int width = Math.min(1024, baseline.length);
        int height = Math.toIntExact((baseline.length + (long) width - 1) / width);
        BufferedImage heatmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        double sum = 0;
        double squares = 0;
        int maximum = 0;
        for (int index = 0; index < baseline.length; index++) {
            int difference = Math.abs(Byte.toUnsignedInt(baseline[index]) - Byte.toUnsignedInt(candidate[index]));
            sum += difference;
            squares += (double) difference * difference;
            maximum = Math.max(maximum, difference);
            heatmap.setRGB(index % width, index / width, 0xff000000 | difference << 16);
        }
        return new Sample(baseline.length, sum, squares, maximum, heatmap);
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record Pair(CapturePlan.Target baseline, CapturePlan.Target candidate) {
    }

    private record Sample(long count, double sum, double sumSquares, int maximum, BufferedImage heatmap) {
    }
}