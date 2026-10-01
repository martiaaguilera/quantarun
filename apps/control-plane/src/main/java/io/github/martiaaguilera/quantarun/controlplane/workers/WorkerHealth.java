package io.github.martiaaguilera.quantarun.controlplane.workers;

import java.time.Duration;
import java.time.Instant;

/**
 * Health derived from heartbeat age, never stored. The thresholds span several heartbeat intervals so that one
 * delayed or lost heartbeat (GC pause, network blip) changes nothing.
 */
public enum WorkerHealth {
    /** Recent heartbeat: eligible for new placements. */
    HEALTHY,
    /** Missed heartbeats: running work is honoured, but no new placements. */
    LATE,
    /** Silent past the offline threshold: the liveness monitor retires the registration. */
    OFFLINE;

    /** {@code now} must come from the same clock that wrote {@code lastSeenAt}: the database's. */
    public static WorkerHealth classify(Instant lastSeenAt, Instant now, WorkerProperties properties) {
        var silence = Duration.between(lastSeenAt, now);
        if (silence.compareTo(properties.lateAfter()) < 0) {
            return HEALTHY;
        }
        if (silence.compareTo(properties.offlineAfter()) < 0) {
            return LATE;
        }
        return OFFLINE;
    }
}
