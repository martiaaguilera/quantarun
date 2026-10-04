package io.github.martiaaguilera.quantarun.controlplane.chaos;

/** An experiment is delivered at most once; one that was never delivered ends as expired or cancelled. */
public enum ChaosStatus {
    PENDING,
    DELIVERED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(ChaosStatus next) {
        return switch (this) {
            case PENDING -> next == DELIVERED || next == EXPIRED || next == CANCELLED;
            case DELIVERED, EXPIRED, CANCELLED -> false;
        };
    }
}
