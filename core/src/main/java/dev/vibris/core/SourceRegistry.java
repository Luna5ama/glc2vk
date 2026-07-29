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

    SourceRegistry(Path pendingRoot, CoreProbe probe) {
        trees = new OwnedSourceTree(pendingRoot);
        this.probe = probe;
    }

    List<Candidate> validate(List<PreparedSourceRef> references) throws Failure {
        if (references.isEmpty()) throw new Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "A source is required.");
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

    synchronized void activate(List<Lease> leases) {
        for (Lease lease : leases) {
            transition(lease, SourceState.QUEUED, SourceState.ACTIVATING, lease.record::beginActivation);
            transition(lease, SourceState.ACTIVATING, SourceState.ACTIVE, lease.record::activated);
        }
    }

    void cleanup(List<Lease> leases) {
        for (Lease lease : leases) cleanup(lease);
    }

    synchronized int size() {
        return sources.size();
    }

    private synchronized void cleanup(Lease lease) {
        if (!sources.containsKey(lease.uuid)) return;
        if (!trees.stillOwned(lease.directory, lease.ownership)) {
            abandonUnsafe(lease);
            return;
        }
        SourceState before = lease.record.state();
        lease.record.release();
        record(lease, before, lease.record.state());
        if (lease.record.active()) {
            before = lease.record.state();
            lease.record.deactivate();
            record(lease, before, lease.record.state());
        }
        if (lease.record.deletionEligible()) {
            before = lease.record.state();
            lease.record.beginDeleting();
            record(lease, before, lease.record.state());
            if (!OwnedSourceTree.delete(lease.directory)) {
                sources.remove(lease.uuid, lease);
                return;
            }
            before = lease.record.state();
            lease.record.deleted();
            record(lease, before, lease.record.state());
            sources.remove(lease.uuid);
        }
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

    static final class Failure extends Exception {
        final ErrorCode code;

        Failure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}