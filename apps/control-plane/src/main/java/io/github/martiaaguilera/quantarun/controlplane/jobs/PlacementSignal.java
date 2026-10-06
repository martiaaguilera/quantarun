package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Tells an idle scheduler that a cycle may now place something: a job was submitted or revived, or an attempt ended
 * and released capacity. Raised after commit, so the woken cycle sees the change. Without it, an idle scheduler noticed
 * new work only after its idle delay; that wait was half of a lightly loaded job's time to start, and under a backlog
 * it cost every freed slot up to 500 ms (BENCHMARKS.md).
 *
 * <p>In-process only, and a hint, never a guarantee: another control-plane instance's scheduler still finds the work
 * on its next idle tick, as before. That keeps PostgreSQL the only coordinator (ADR-0001).
 */
@Component
public class PlacementSignal {

    /** At most one pending wake-up: a thousand submissions in one burst need one cycle, not a thousand. */
    private final Semaphore pending = new Semaphore(0);

    void raiseAfterCommit() {
        AfterCommit.run(this::raise);
    }

    void raise() {
        if (pending.availablePermits() == 0) {
            pending.release();
        }
    }

    /** Waits until signalled or {@code timeout} passes, whichever is first; consumes any pending signal. */
    public void await(Duration timeout) throws InterruptedException {
        if (pending.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            pending.drainPermits();
        }
    }
}
