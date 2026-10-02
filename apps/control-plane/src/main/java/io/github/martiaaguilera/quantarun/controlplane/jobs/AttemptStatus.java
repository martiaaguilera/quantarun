package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.EnumSet;
import java.util.Set;

/** Attempt lifecycle (docs/SPEC.md §4). Only ASSIGNED and RUNNING hold a reservation and a lease. */
public enum AttemptStatus {
    ASSIGNED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    LOST,
    CANCELLED;

    private static final Set<AttemptStatus> ACTIVE = EnumSet.of(ASSIGNED, RUNNING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean canTransitionTo(AttemptStatus next) {
        return switch (this) {
            case ASSIGNED -> next == RUNNING || next == LOST || next == CANCELLED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == LOST || next == CANCELLED;
            case SUCCEEDED, FAILED, LOST, CANCELLED -> false;
        };
    }
}
