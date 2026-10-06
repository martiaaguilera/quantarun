package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps lease expiry about the workers' silence, not the control plane's. A lease runs out when nobody renews it, and
 * nobody can while this control plane is down, cannot reach the database, or is stalled. Whoever first reaches the
 * database after such a gap (the reaper's tick, or a worker's heartbeat, claim, report or checkpoint) extends every
 * active lease before any lease is judged; otherwise the first report after a database outage was refused as
 * LEASE_EXPIRED and its finished work run again (ENGINEERING_LOG, 2026-10-04). Never having heard counts as a gap, so a
 * restart is the same case.
 *
 * <p>Callers invoke {@link #catchUp()} outside their own transaction: the extension locks every active attempt and
 * must commit before a caller locks one of them.
 */
public class LeaseContinuity {

    private static final Logger log = LoggerFactory.getLogger(LeaseContinuity.class);
    /** Hearing is confirmed with a round trip at most this often while traffic flows. */
    private static final Duration RECHECK = Duration.ofSeconds(1);

    private final JobAttempts attempts;
    private final Clock clock;
    private final Duration deafAfter;
    private final boolean enabled;
    private @Nullable Instant lastHeard;

    /**
     * @param enabled false only in tests that expire leases on purpose and call the reaper by hand; the continuity
     *     tests build their own instance with a settable clock.
     */
    public LeaseContinuity(JobAttempts attempts, Clock clock, Duration deafAfter, boolean enabled) {
        this.attempts = attempts;
        this.clock = clock;
        this.deafAfter = deafAfter;
        this.enabled = enabled;
    }

    /**
     * Confirms the database is reachable, extending every active lease first when it was not reached for longer than
     * {@code deafAfter}. Throws while the database is unreachable, so time spent failing never counts as hearing.
     * Serialised: concurrent first callers after a gap wait for one extension instead of judging leases before it.
     */
    public synchronized void catchUp() {
        if (!enabled) {
            return;
        }
        var now = clock.instant();
        var previous = lastHeard;
        if (previous != null && Duration.between(previous, now).compareTo(RECHECK) < 0) {
            return;
        }
        if (previous == null || Duration.between(previous, now).compareTo(deafAfter) > 0) {
            var extended = attempts.extendActiveLeasesAfterRestart();
            log.atWarn()
                    .addKeyValue("deafFor", previous == null ? null : Duration.between(previous, now))
                    .addKeyValue("extendedLeases", extended)
                    .log("Could not hear workers for a while; extended leases before judging any");
        } else {
            attempts.confirmDatabaseReachable();
        }
        lastHeard = now;
    }
}
