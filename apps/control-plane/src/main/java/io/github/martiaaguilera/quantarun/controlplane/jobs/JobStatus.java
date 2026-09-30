package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Job lifecycle (docs/SPEC.md §3). This table is the single definition of legal transitions; repositories check
 * it before every conditional update, so an illegal transition fails in Java before it could reach SQL.
 */
public enum JobStatus {
    QUEUED,
    SCHEDULED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    DEAD,
    CANCELLED;

    private static final Map<JobStatus, Set<JobStatus>> TRANSITIONS = new EnumMap<>(JobStatus.class);

    static {
        TRANSITIONS.put(QUEUED, EnumSet.of(SCHEDULED, CANCELLED));
        TRANSITIONS.put(SCHEDULED, EnumSet.of(RUNNING, RETRY_WAIT, DEAD, CANCELLED));
        TRANSITIONS.put(RUNNING, EnumSet.of(SUCCEEDED, RETRY_WAIT, FAILED, DEAD, CANCELLED));
        TRANSITIONS.put(RETRY_WAIT, EnumSet.of(SCHEDULED, CANCELLED));
        // DEAD is final except for an explicit revive by an operator (invariant I13).
        TRANSITIONS.put(DEAD, EnumSet.of(QUEUED));
        TRANSITIONS.put(SUCCEEDED, EnumSet.noneOf(JobStatus.class));
        TRANSITIONS.put(FAILED, EnumSet.noneOf(JobStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(JobStatus.class));
    }

    public boolean canTransitionTo(JobStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /** Throws when {@code this -> next} is not in the transition table. */
    public void requireTransitionTo(JobStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalJobTransitionException(this, next);
        }
    }

    /** No further automatic progress. DEAD counts as final even though an operator may revive it. */
    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD || this == CANCELLED;
    }

    /** Waiting for the scheduler: the only states from which a job can be placed on a worker. */
    public boolean isRunnable() {
        return this == QUEUED || this == RETRY_WAIT;
    }

    /** Holds (or is about to hold) a worker reservation; cancellation must be cooperative. */
    public boolean hasActiveAttempt() {
        return this == SCHEDULED || this == RUNNING;
    }
}
