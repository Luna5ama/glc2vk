package dev.vibris.core;

import dev.vibris.protocol.v1.SceneContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CoreProbe {
    private static final int TRACE_LIMIT = 1_024;
    private static final int HISTORY_LIMIT = 128;

    private final List<String> executionOrder = new ArrayList<>();
    private final List<String> executionEvents = new ArrayList<>();
    private final List<SceneContext> contextSnapshots = new ArrayList<>();
    private final List<JobTrace> jobTraces = new ArrayList<>();
    private final List<SourceTransition> sourceTransitions = new ArrayList<>();
    private final Map<String, List<String>> sourceStates = new LinkedHashMap<>();
    private final Map<String, Integer> executionCounts = new LinkedHashMap<>();
    private int concurrentJobs;
    private int maxConcurrentJobs;
    private int requestRegistrySize;
    private int requestRegistryPeak;
    private int sourceRegistrySize;
    private int sourceRegistryPeak;
    private int queueSize;
    private int queuePeak;

    synchronized void jobStarted(String requestId) {
        boundedAdd(executionOrder, requestId, TRACE_LIMIT);
        executionCounts.merge(requestId, 1, Integer::sum);
        trimMap(executionCounts);
        concurrentJobs++;
        maxConcurrentJobs = Math.max(maxConcurrentJobs, concurrentJobs);
    }

    synchronized void contextApplied(String requestId, String workspaceId, SceneContext context) {
        boundedAdd(contextSnapshots, context, TRACE_LIMIT);
        boundedAdd(jobTraces, new JobTrace(requestId, workspaceId, context), TRACE_LIMIT);
    }

    synchronized void event(String requestId, String event) {
        boundedAdd(executionEvents, requestId + ":" + event, TRACE_LIMIT);
    }

    synchronized void jobStopped() {
        concurrentJobs--;
    }

    synchronized void sourceTransition(String uuid, String from, String to) {
        List<String> states = sourceStates.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        trimMap(sourceStates);
        if (states.isEmpty() && !from.isEmpty()) states.add(from);
        states.add(to);
        boundedAdd(sourceTransitions, new SourceTransition(uuid, from, to), TRACE_LIMIT);
    }

    synchronized void registries(int requests, int sources, int queued) {
        requestRegistrySize = requests;
        requestRegistryPeak = Math.max(requestRegistryPeak, requests);
        sourceRegistrySize = sources;
        sourceRegistryPeak = Math.max(sourceRegistryPeak, sources);
        queueSize = queued;
        queuePeak = Math.max(queuePeak, queued);
    }

    public synchronized List<String> executionOrder() {
        return List.copyOf(executionOrder);
    }

    public synchronized List<String> executionEvents() {
        return List.copyOf(executionEvents);
    }

    public synchronized List<SceneContext> contextSnapshots() {
        return List.copyOf(contextSnapshots);
    }

    public synchronized List<String> sourceStates(String uuid) {
        return List.copyOf(sourceStates.getOrDefault(uuid, List.of()));
    }

    public synchronized int maxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public synchronized int executionCount(String requestId) {
        return executionCounts.getOrDefault(requestId, 0);
    }

    public synchronized int requestRegistrySize() {
        return requestRegistrySize;
    }

    public int requestRegistryCapacity() {
        return VibrisCoreEngine.REQUEST_REGISTRY_CAPACITY;
    }

    public synchronized int sourceRegistrySize() {
        return sourceRegistrySize;
    }

    public int sourceRegistryCapacity() {
        return SourceRegistry.CAPACITY;
    }

    public synchronized int queueSize() {
        return queueSize;
    }

    public int queueCapacity() {
        return FairJobScheduler.CAPACITY;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(jobTraces), List.copyOf(sourceTransitions), List.copyOf(executionOrder),
            List.copyOf(executionEvents), Map.copyOf(executionCounts), maxConcurrentJobs, queuePeak,
            requestRegistrySize, requestRegistryPeak, sourceRegistrySize, sourceRegistryPeak);
    }

    private static <T> void boundedAdd(List<T> values, T value, int limit) {
        if (values.size() == limit) values.removeFirst();
        values.add(value);
    }

    private static void trimMap(Map<?, ?> values) {
        while (values.size() > HISTORY_LIMIT) values.remove(values.keySet().iterator().next());
    }

    public record JobTrace(String requestId, String workspaceId, SceneContext context) {
    }

    public record SourceTransition(String uuid, String from, String to) {
    }

    public record Snapshot(List<JobTrace> jobTraces, List<SourceTransition> sourceTransitions,
                           List<String> executionOrder, List<String> executionEvents,
                           Map<String, Integer> executionCounts,
                           int maxConcurrentJobs, int queuePeak, int requestRegistrySize,
                           int requestRegistryPeak, int sourceRegistrySize, int sourceRegistryPeak) {
    }
}