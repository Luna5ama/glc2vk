package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CapturePlanBuilder {
    private static final int MAX_ACTIONS = 64;

    int waitFrames(CoreJob job) throws RuntimeJobExecutor.Failure {
        try {
            requireActionLimit(job);
            long frames = job.submission.hasRecipe() ? recipeFrames(job) : actionFrames(job);
            if (frames > Integer.MAX_VALUE) {
                throw new ArithmeticException("frame count");
            }
            return (int) frames;
        } catch (ArithmeticException exception) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.INTERNAL_ERROR, "Requested frame count is too large.");
        }
    }

    Plan build(CoreJob job, ResourceCatalog catalog) throws RuntimeJobExecutor.Failure {
        try {
            List<CapturePlan.Target> targets = new ArrayList<>();
            if (job.submission.hasActions()) {
                for (Action action : job.submission.getActions().getActionsList()) addAction(targets, action, catalog);
            } else if (job.submission.getRecipe().hasReloadAndCapture()) {
                var recipe = job.submission.getRecipe().getReloadAndCapture();
                targets.add(screenshot(catalog, format(recipe.getScreenshotFormat(), CapturePlan.ArtifactFormat.PNG),
                    "screenshot"));
            } else if (job.submission.getRecipe().hasCaptureDebugBundle()) {
                addDebugBundle(targets, job, catalog);
            }
            if (targets.size() > MAX_ACTIONS) {
                throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture target limit exceeded.");
            }
            CapturePlan plan = new CapturePlan(targets);
            return new Plan(plan, validateAndEstimate(plan, catalog));
        } catch (IllegalArgumentException exception) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture plan is invalid.");
        }
    }

    private static void addDebugBundle(List<CapturePlan.Target> targets, CoreJob job, ResourceCatalog catalog) {
        var recipe = job.submission.getRecipe().getCaptureDebugBundle();
        if (recipe.getScreenshot()) targets.add(screenshot(catalog, CapturePlan.ArtifactFormat.PNG, "screenshot"));
        recipe.getTexturesList().forEach(name -> targets.add(target(
            ResourceCatalog.ResourceKind.TEXTURE, name, CapturePlan.ArtifactFormat.RAW, name, 0, 0)));
        recipe.getBuffersList().forEach(name -> targets.add(target(
            ResourceCatalog.ResourceKind.BUFFER, name, CapturePlan.ArtifactFormat.BIN, name, 0, 0)));
    }

    private static void addAction(List<CapturePlan.Target> targets, Action action, ResourceCatalog catalog) {
        if (action.hasCaptureScreenshot()) {
            var capture = action.getCaptureScreenshot();
            targets.add(screenshot(catalog, format(capture.getFormat(), CapturePlan.ArtifactFormat.PNG),
                capture.getArtifactName().isBlank() ? "screenshot" : capture.getArtifactName()));
        } else if (action.hasDumpTexture()) {
            var dump = action.getDumpTexture();
            targets.add(target(ResourceCatalog.ResourceKind.TEXTURE, dump.getLogicalName(),
                format(dump.getFormat(), CapturePlan.ArtifactFormat.RAW), dump.getArtifactName(),
                dump.getMipLevel(), dump.getLayer()));
        } else if (action.hasDumpBuffer()) {
            var dump = action.getDumpBuffer();
            targets.add(target(ResourceCatalog.ResourceKind.BUFFER, dump.getLogicalName(),
                format(dump.getFormat(), CapturePlan.ArtifactFormat.BIN), dump.getArtifactName(), 0, 0));
        }
    }

    private static CapturePlan.Target screenshot(ResourceCatalog catalog, CapturePlan.ArtifactFormat format,
        String artifactName) {
        String name = catalog.resources().stream()
            .filter(resource -> resource.kind() == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER)
            .map(ResourceCatalog.ResourceDescriptor::logicalName).findFirst().orElse("final_framebuffer");
        return target(ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, name, format, artifactName, 0, 0);
    }

    private static CapturePlan.Target target(ResourceCatalog.ResourceKind kind, String name,
        CapturePlan.ArtifactFormat format, String artifactName, int mip, int layer) {
        return new CapturePlan.Target(kind, name, format, artifactName, mip, layer);
    }

    private static long validateAndEstimate(CapturePlan plan, ResourceCatalog catalog)
        throws RuntimeJobExecutor.Failure {
        long bytes = 0;
        Set<String> names = new HashSet<>();
        try {
            for (CapturePlan.Target target : plan.targets()) {
                if (!supported(target)) {
                    throw new RuntimeJobExecutor.Failure(
                        ErrorCode.CAPTURE_FAILED, "Capture resource kind and format are incompatible.");
                }
                if (!names.add(canonical(target.fileName())) || !names.add(canonical(target.metadataFileName()))) {
                    throw new RuntimeJobExecutor.Failure(
                        ErrorCode.CAPTURE_FAILED, "Capture artifact names are repeated.");
                }
                ResourceCatalog.ResourceDescriptor resource = find(catalog, target);
                if (target.mipLevel() >= Math.max(1, resource.mipLevels()) ||
                    target.layer() >= Math.max(1, resource.layers())) {
                    throw missing(target.logicalName());
                }
                bytes = Math.addExact(bytes, resource.byteSize());
            }
            return bytes;
        } catch (ArithmeticException exception) {
            throw new RuntimeJobExecutor.Failure(
                ErrorCode.ARTIFACT_JOB_TOO_LARGE, "Artifact estimate is too large.");
        }
    }

    private static ResourceCatalog.ResourceDescriptor find(ResourceCatalog catalog, CapturePlan.Target target)
        throws RuntimeJobExecutor.Failure {
        return catalog.resources().stream()
            .filter(resource -> resource.kind() == target.kind() && resource.logicalName().equals(target.logicalName()))
            .findFirst().orElseThrow(() -> missing(target.logicalName()));
    }

    private static RuntimeJobExecutor.Failure missing(String name) {
        return new RuntimeJobExecutor.Failure(
            ErrorCode.CAPTURE_RESOURCE_NOT_FOUND, "Capture resource was not found: " + name);
    }

    private static long actionFrames(CoreJob job) {
        long frames = 0;
        for (Action action : job.submission.getActions().getActionsList()) {
            if (action.hasWaitFrames()) frames = Math.addExact(frames, action.getWaitFrames().getFrameCount());
        }
        return frames;
    }

    private static void requireActionLimit(CoreJob job) throws RuntimeJobExecutor.Failure {
        if (job.submission.hasActions() && job.submission.getActions().getActionsCount() > MAX_ACTIONS) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Action limit exceeded.");
        }
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean supported(CapturePlan.Target target) {
        return switch (target.kind()) {
            case FINAL_FRAMEBUFFER -> target.format() == CapturePlan.ArtifactFormat.PNG;
            case TEXTURE -> target.format() == CapturePlan.ArtifactFormat.PNG ||
                target.format() == CapturePlan.ArtifactFormat.RAW;
            case BUFFER -> target.format() == CapturePlan.ArtifactFormat.BIN;
        };
    }

    private static long recipeFrames(CoreJob job) {
        var recipe = job.submission.getRecipe();
        if (recipe.hasReloadAndCapture()) return recipe.getReloadAndCapture().getWarmupFrames();
        if (recipe.hasCaptureDebugBundle()) return recipe.getCaptureDebugBundle().getWarmupFrames();
        if (recipe.hasAbCompare()) return recipe.getAbCompare().getWarmupFrames();
        return 0;
    }

    private static CapturePlan.ArtifactFormat format(dev.vibris.protocol.v1.ArtifactFormat format,
        CapturePlan.ArtifactFormat fallback) {
        return switch (format) {
            case ARTIFACT_FORMAT_PNG -> CapturePlan.ArtifactFormat.PNG;
            case ARTIFACT_FORMAT_EXR -> CapturePlan.ArtifactFormat.EXR;
            case ARTIFACT_FORMAT_RAW -> CapturePlan.ArtifactFormat.RAW;
            case ARTIFACT_FORMAT_BIN -> CapturePlan.ArtifactFormat.BIN;
            default -> fallback;
        };
    }

    record Plan(CapturePlan capture, long estimatedBytes) {
    }
}