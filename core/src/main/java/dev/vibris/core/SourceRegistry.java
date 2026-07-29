package dev.vibris.core;

import dev.vibris.core.source.SourceRecord;
import dev.vibris.core.source.SourceState;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.PreparedSourceRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SourceRegistry {
    static final int CAPACITY = 128;
    private final OwnedSourceTree trees;
    private final CoreProbe probe;
    private final Map<String, Lease> sources = new HashMap<>();
    private Lease activeSource;

    SourceRegistry(Path pendingRoot, CoreProbe probe) {
        trees = new OwnedSourceTree(pendingRoot);
        this.probe = probe;
    }

    List<Candidate> validate(List<PreparedSourceRef> references) throws Failure {
        return validate(references, 1);
    }

    List<Candidate> validate(List<PreparedSourceRef> references, int expectedCount) throws Failure {
        if (references.isEmpty()) throw new Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "A source is required.");
        if (references.size() != expectedCount) {
            throw new Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Prepared source count does not match the job.");
        }
        Set<String> unique = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>(references.size());
        for (PreparedSourceRef reference : references) {
            String uuid = requireUuid(reference.getUuid());
            if (!unique.add(uuid)) throw new Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is repeated.");
            OwnedSourceTree.Inspection inspection = trees.inspect(uuid);
            if (inspection.fileCount() != reference.getFileCount() ||
                inspection.totalBytes() != reference.getTotalBytes()) {
                throw new Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "Source metadata does not match its directory.");
            }
            candidates.add(new Candidate(
                uuid, inspection.directory(), reference.getFileCount(), reference.getTotalBytes()));
        }
        return candidates;
    }

    synchronized List<Lease> reserve(List<Candidate> candidates) throws Failure {
        if (sources.size() + candidates.size() > CAPACITY) {
            throw new Failure(ErrorCode.QUEUE_FULL, "The source registry is full.");
        }
        List<OwnedSourceTree.Ownership> ownerships = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (sources.containsKey(candidate.uuid)) {
                throw new Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is already owned.");
            }
            ownerships.add(trees.reserve(candidate.directory, candidate.fileCount, candidate.totalBytes));
        }
        List<Lease> reserved = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            Lease lease = new Lease(
                candidate.uuid, candidate.directory, ownerships.get(index), new SourceRecord(candidate.uuid, 1));
            sources.put(candidate.uuid, lease);
            reserved.add(lease);
        }
        return reserved;
    }

    synchronized void accept(List<Lease> reserved) {
        for (Lease lease : reserved) {
            transition(lease, "", SourceState.VALIDATED, lease.record::state);
            transition(lease, SourceState.VALIDATED, SourceState.QUEUED, lease.record::queue);
        }
    }

    synchronized void reject(List<Lease> reserved) {
        for (Lease lease : reserved) sources.remove(lease.uuid, lease);
    }

    synchronized Activation beginActivation(Lease lease) throws Failure {
        requireOwned(lease);
        transition(lease, SourceState.QUEUED, SourceState.ACTIVATING, lease.record::beginActivation);
        return new Activation(lease, activeSource);
    }

    synchronized void commitActivation(Activation activation) {
        if (activeSource != activation.previous) throw new IllegalStateException("active source changed");
        Lease next = activation.next;
        transition(next, SourceState.ACTIVATING, SourceState.ACTIVE, next.record::activated);
        activeSource = next;
        if (activation.previous != null) {
            Lease previous = activation.previous;
            SourceState before = previous.record.state();
            previous.record.deactivate();
            record(previous, before, previous.record.state());
            deleteIfEligible(previous);
        }
    }

    synchronized void failActivation(Activation activation) {
        failActivation(activation.next);
    }

    synchronized void failActivation(Lease lease) {
        SourceState before = lease.record.state();
        lease.record.failed();
        record(lease, before, lease.record.state());
    }

    synchronized void requireOwned(Lease lease) throws Failure {
        if (!sources.containsKey(lease.uuid) || !trees.stillOwned(lease.directory, lease.ownership)) {
            throw new Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Prepared source identity changed.");
        }
    }

    synchronized String activeUuid() {
        return activeSource == null ? "" : activeSource.uuid;
    }

    void cleanup(List<Lease> leases) {
        release(leases, false);
    }

    void release(List<Lease> leases, boolean retainActive) {
        for (Lease lease : leases) release(lease, retainActive);
    }

    synchronized int size() {
        return sources.size();
    }

    private synchronized void release(Lease lease, boolean retainActive) {
        if (!sources.containsKey(lease.uuid)) return;
        if (!trees.stillOwned(lease.directory, lease.ownership)) {
            abandonUnsafe(lease);
            return;
        }
        SourceState before = lease.record.state();
        lease.record.release();
        record(lease, before, lease.record.state());
        if (lease.record.active() && !retainActive) {
            before = lease.record.state();
            lease.record.deactivate();
            record(lease, before, lease.record.state());
            if (activeSource == lease) activeSource = null;
        }
        deleteIfEligible(lease);
    }

    private void abandonUnsafe(Lease lease) {
        SourceState before = lease.record.state();
        if (before == SourceState.VALIDATED || before == SourceState.QUEUED || before == SourceState.ACTIVATING) {
            lease.record.failed();
            record(lease, before, lease.record.state());
        }
        before = lease.record.state();
        lease.record.release();
        record(lease, before, lease.record.state());
        if (lease.record.active()) {
            before = lease.record.state();
            lease.record.deactivate();
            record(lease, before, lease.record.state());
        }
        sources.remove(lease.uuid, lease);
    }

    synchronized void close() {
        if (activeSource != null && activeSource.record.active()) {
            SourceState before = activeSource.record.state();
            activeSource.record.deactivate();
            record(activeSource, before, activeSource.record.state());
        }
        activeSource = null;
        new ArrayList<>(sources.values()).forEach(this::deleteIfEligible);
    }

    private void deleteIfEligible(Lease lease) {
        if (!lease.record.deletionEligible()) return;
        SourceState before = lease.record.state();
        lease.record.beginDeleting();
        record(lease, before, lease.record.state());
        if (!OwnedSourceTree.delete(lease.directory)) {
            sources.remove(lease.uuid, lease);
            return;
        }
        before = lease.record.state();
        lease.record.deleted();
        record(lease, before, lease.record.state());
        sources.remove(lease.uuid, lease);
    }

    private static String requireUuid(String value) throws Failure {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is invalid.");
        }
    }

    private void transition(Lease lease, SourceState from, SourceState to, Runnable action) {
        action.run();
        record(lease, from, to);
    }

    private void transition(Lease lease, String from, SourceState to, Runnable action) {
        action.run();
        probe.sourceTransition(lease.uuid, from, to.name());
    }

    private void record(Lease lease, SourceState from, SourceState to) {
        probe.sourceTransition(lease.uuid, from.name(), to.name());
    }

    record Candidate(String uuid, Path directory, long fileCount, long totalBytes) {
    }

    record Lease(
        String uuid,
        Path directory,
        OwnedSourceTree.Ownership ownership,
        SourceRecord record
    ) {
    }

    record Activation(Lease next, Lease previous) {
    }

    static final class Failure extends Exception {
        final ErrorCode code;

        Failure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}