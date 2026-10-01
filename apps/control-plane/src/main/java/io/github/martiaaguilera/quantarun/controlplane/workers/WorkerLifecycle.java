package io.github.martiaaguilera.quantarun.controlplane.workers;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Stored worker lifecycle (docs/SPEC.md §5). OFFLINE and DEREGISTERED are terminal for a registration: a process
 * that comes back registers again under a new id, so it can never resurrect attempts that were already recovered.
 */
public enum WorkerLifecycle {
    ACTIVE,
    DRAINING,
    OFFLINE,
    DEREGISTERED;

    private static final Map<WorkerLifecycle, Set<WorkerLifecycle>> TRANSITIONS = new EnumMap<>(WorkerLifecycle.class);

    static {
        TRANSITIONS.put(ACTIVE, EnumSet.of(DRAINING, OFFLINE, DEREGISTERED));
        TRANSITIONS.put(DRAINING, EnumSet.of(OFFLINE, DEREGISTERED));
        TRANSITIONS.put(OFFLINE, EnumSet.noneOf(WorkerLifecycle.class));
        TRANSITIONS.put(DEREGISTERED, EnumSet.noneOf(WorkerLifecycle.class));
    }

    public boolean canTransitionTo(WorkerLifecycle next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public void requireTransitionTo(WorkerLifecycle next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal worker transition " + this + " -> " + next);
        }
    }

    /** Still a member of the fleet: heartbeats are accepted and running attempts are honoured. */
    public boolean isLive() {
        return this == ACTIVE || this == DRAINING;
    }
}
