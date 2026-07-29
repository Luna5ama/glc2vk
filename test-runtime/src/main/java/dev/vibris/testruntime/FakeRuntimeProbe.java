package dev.vibris.testruntime;

import dev.vibris.core.CoreProbe;
import dev.vibris.protocol.v1.SceneContext;

import java.util.List;

public final class FakeRuntimeProbe {
    private final FakeRuntimeAdapter runtime;
    private final CoreProbe core;

    FakeRuntimeProbe(FakeRuntimeAdapter runtime, CoreProbe core) {
        this.runtime = runtime;
        this.core = core;
    }

    public void pauseExecution() {
        runtime.pauseExecution();
    }

    public void resumeExecution() {
        runtime.resumeExecution();
    }

    public List<String> executionOrder() {
        return core.executionOrder();
    }

    public List<String> executionEvents() {
        return core.executionEvents();
    }

    public List<SceneContext> contextSnapshots() {
        return core.contextSnapshots();
    }

    public List<String> sourceStates(String uuid) {
        return core.sourceStates(uuid);
    }

    public int maxConcurrentJobs() {
        return core.maxConcurrentJobs();
    }

    public int executionCount(String requestId) {
        return core.executionCount(requestId);
    }

    public int requestRegistrySize() {
        return core.requestRegistrySize();
    }

    public int requestRegistryCapacity() {
        return core.requestRegistryCapacity();
    }

    public int sourceRegistrySize() {
        return core.sourceRegistrySize();
    }

    public int sourceRegistryCapacity() {
        return core.sourceRegistryCapacity();
    }

    public int queueSize() {
        return core.queueSize();
    }

    public int queueCapacity() {
        return core.queueCapacity();
    }

    int maxConcurrentRuntimeOperations() {
        return runtime.maxConcurrentOperations();
    }

    CoreProbe.Snapshot snapshot() {
        return core.snapshot();
    }
}