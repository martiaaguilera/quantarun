package io.github.martiaaguilera.quantarun.controlplane.reliability;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.LeaseContinuity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Recovers attempts whose leases expired: the worker is presumed lost, its reservation is released and the job is
 * retried or ends (docs/SPEC.md §3, §8).
 *
 * <p>Nothing is reaped until the leases have been extended after startup. While this control plane was down nobody
 * could renew a lease, so on startup every running attempt may look expired; reaping then would declare the whole
 * fleet's work lost and run it again. This is the lease counterpart of the worker startup grace (ENGINEERING_LOG,
 * 2026-10-01). {@code @Scheduled} tasks start at context refresh, before {@link ApplicationReadyEvent}, hence the flag.
 *
 * <p>The same holds after any stretch in which this control plane could not hear heartbeats while it kept running: a
 * database outage, a long pause of the process. {@link LeaseContinuity} extends the leases again before anything is
 * reaped, and before any worker request judges a lease (ENGINEERING_LOG, 2026-10-04).
 */
class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    static final int BATCH_SIZE = 50;
    /** Bounds one tick, so a huge backlog (say 10,000 lost attempts) cannot monopolise the scheduling thread. */
    static final int MAX_BATCHES_PER_TICK = 20;

    private final JobAttempts attempts;
    private final LeaseContinuity continuity;
    private volatile boolean leasesExtended;

    LeaseReaper(JobAttempts attempts, LeaseContinuity continuity) {
        this.attempts = attempts;
        this.continuity = continuity;
    }

    @EventListener(ApplicationReadyEvent.class)
    void extendLeasesAfterStartup() {
        continuity.catchUp();
        leasesExtended = true;
        log.atInfo().log("Lease reaper armed");
    }

    /**
     * Each batch is its own transaction, so a full batch commits before the next one is locked and a failure loses
     * at most one batch of progress; the next tick retries it.
     */
    @Scheduled(fixedDelayString = "${quantarun.reliability.reaper-interval:1s}")
    void reap() {
        if (!leasesExtended) {
            return;
        }
        try {
            continuity.catchUp();
            var recovered = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
                var count = attempts.recoverExpiredLeases(BATCH_SIZE);
                recovered += count;
                if (count < BATCH_SIZE) {
                    break;
                }
            }
            if (recovered > 0) {
                log.atInfo().addKeyValue("recovered", recovered).log("Recovered attempts with expired leases");
            }
        } catch (DataAccessException | CannotCreateTransactionException e) {
            log.atWarn().addKeyValue("error", e.getMessage()).log("Lease reaping failed; retrying next tick");
        }
    }
}
