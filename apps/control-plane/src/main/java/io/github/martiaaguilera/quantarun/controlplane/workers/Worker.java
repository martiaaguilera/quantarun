package io.github.martiaaguilera.quantarun.controlplane.workers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A worker registration as seen by the control plane.
 *
 * @param observedAt database time of the read; health is derived from it, not from the JVM clock.
 */
public record Worker(
        UUID id,
        String name,
        String version,
        WorkerLifecycle lifecycle,
        List<String> labels,
        WorkerResources capacity,
        WorkerResources reserved,
        Instant registeredAt,
        Instant lastSeenAt,
        Instant observedAt) {

    /** A retired registration is gone whatever its last heartbeat says; only live members have a meaningful health. */
    public WorkerHealth health(WorkerProperties properties) {
        if (!lifecycle.isLive()) {
            return WorkerHealth.OFFLINE;
        }
        return WorkerHealth.classify(lastSeenAt, observedAt, properties);
    }
}
