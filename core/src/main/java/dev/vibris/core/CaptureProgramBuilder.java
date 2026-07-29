package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.CaptureTarget;
import dev.vibris.protocol.v1.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CaptureProgramBuilder {
    private static final int MAX_ACTIONS = 64;

    ActionProgram actions(CoreJob job, ResourceCatalog catalog) throws RuntimeJobExecutor.Failure {
        if (job.submission.getActions().getActionsCount() > MAX_ACTIONS) throw invalid("Action limit exceeded.");
        List<ActionStep> steps = new ArrayList<>();
        List<CapturePlan.Target> group = new ArrayList<>();
        Set<String> artifactNames = new HashSet<>();
        long estimatedBytes = 0;
        for (Action action : job.submission.getActions().getActionsList()) {
            if (action.hasResetTemporalState()) {
                estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes);
                steps.add(ActionStep.reset());
            } else if (action.hasWaitFrames()) {
                estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes);
                steps.add(ActionStep.waitFrames(action.getWaitFrames().getFrameCount()));
            } else if (action.hasCaptureScreenshot() || action.hasDumpTexture() || action.hasDumpBuffer()) {
                CapturePlanBuilder.addAction(group, action, catalog);
            } else {
                throw invalid("Action is not supported.");
            }
        }
        estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes);
        return new ActionProgram(List.copyOf(steps), estimatedBytes);
    }

    AbProgram ab(CoreJob job, ResourceCatalog catalog) throws RuntimeJobExecutor.Failure {
        var recipe = job.submission.getRecipe().getAbCompare();
        if (recipe.getCapturesCount() == 0 || recipe.getCapturesCount() > MAX_ACTIONS) {
            throw invalid("A/B capture count is invalid.");
        }
        List<CapturePlan.Target> baseline = new ArrayList<>();
        List<CapturePlan.Target> candidate = new ArrayList<>();
        for (int index = 0; index < recipe.getCapturesCount(); index++) {
            CaptureTarget capture = recipe.getCaptures(index);
            baseline.add(abTarget(catalog, capture, "a-" + index));
            candidate.add(abTarget(catalog, capture, "b-" + index));
        }
        CapturePlanBuilder.Plan a = CapturePlanBuilder.plan(baseline, catalog);
        CapturePlanBuilder.Plan b = CapturePlanBuilder.plan(candidate, catalog);
        long estimate;
        try {
            estimate = Math.addExact(a.estimatedBytes(), b.estimatedBytes());
        } catch (ArithmeticException exception) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.ARTIFACT_JOB_TOO_LARGE, "A/B estimate is too large.");
        }
        return new AbProgram(a.capture(), b.capture(), estimate);
    }

    private static long flush(
        List<CapturePlan.Target> group,
        List<ActionStep> steps,
        ResourceCatalog catalog,
        Set<String> artifactNames,
        long estimatedBytes
    ) throws RuntimeJobExecutor.Failure {
        if (group.isEmpty()) return estimatedBytes;
        CapturePlanBuilder.Plan planned = CapturePlanBuilder.plan(List.copyOf(group), catalog);
        for (CapturePlan.Target target : group) {
            requireUnique(artifactNames, target.fileName());
            if (target.format() == CapturePlan.ArtifactFormat.RAW ||
                target.format() == CapturePlan.ArtifactFormat.BIN) {
                requireUnique(artifactNames, target.metadataFileName());
            }
        }
        group.clear();
        steps.add(ActionStep.capture(planned.capture()));
        try {
            return Math.addExact(estimatedBytes, planned.estimatedBytes());
        } catch (ArithmeticException exception) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.ARTIFACT_JOB_TOO_LARGE, "Artifact estimate is too large.");
        }
    }

    private static void requireUnique(Set<String> names, String name) throws RuntimeJobExecutor.Failure {
        if (!names.add(name.toLowerCase(Locale.ROOT))) throw invalid("Capture artifact names are repeated.");
    }

    private static CapturePlan.Target abTarget(ResourceCatalog catalog, CaptureTarget capture, String artifactName)
        throws RuntimeJobExecutor.Failure {
        CapturePlan.ArtifactFormat format = CapturePlanBuilder.format(capture.getFormat(), switch (capture.getKind()) {
            case CAPTURE_TARGET_KIND_SCREENSHOT -> CapturePlan.ArtifactFormat.PNG;
            case CAPTURE_TARGET_KIND_TEXTURE -> CapturePlan.ArtifactFormat.RAW;
            case CAPTURE_TARGET_KIND_BUFFER -> CapturePlan.ArtifactFormat.BIN;
            default -> throw invalid("A/B capture kind is invalid.");
        });
        return switch (capture.getKind()) {
            case CAPTURE_TARGET_KIND_SCREENSHOT -> CapturePlanBuilder.screenshot(catalog, format, artifactName);
            case CAPTURE_TARGET_KIND_TEXTURE -> CapturePlanBuilder.target(
                ResourceCatalog.ResourceKind.TEXTURE, capture.getName(), format, artifactName, 0, 0);
            case CAPTURE_TARGET_KIND_BUFFER -> CapturePlanBuilder.target(
                ResourceCatalog.ResourceKind.BUFFER, capture.getName(), format, artifactName, 0, 0);
            default -> throw invalid("A/B capture kind is invalid.");
        };
    }

    private static RuntimeJobExecutor.Failure invalid(String message) {
        return new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, message);
    }

    enum ActionType { RESET, WAIT, CAPTURE }

    record ActionStep(ActionType type, int frames, CapturePlan capture) {
        static ActionStep reset() { return new ActionStep(ActionType.RESET, 0, null); }
        static ActionStep waitFrames(int frames) { return new ActionStep(ActionType.WAIT, frames, null); }
        static ActionStep capture(CapturePlan capture) { return new ActionStep(ActionType.CAPTURE, 0, capture); }
    }

    record ActionProgram(List<ActionStep> steps, long estimatedBytes) {
    }

    record AbProgram(CapturePlan baseline, CapturePlan candidate, long estimatedBytes) {
    }
}