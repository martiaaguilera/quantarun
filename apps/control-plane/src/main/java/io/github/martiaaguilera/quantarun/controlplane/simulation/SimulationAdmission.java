package io.github.martiaaguilera.quantarun.controlplane.simulation;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds how many simulations run at once. A run is synchronous and CPU-bound (a 20,000-job comparison of all six
 * policies takes about 12 s of a core, BENCHMARKS.md), and any project key may start one, so without a bound one tenant
 * could occupy every core and starve the scheduler that places every tenant's work (THREAT_MODEL.md, found in the
 * Phase 14 review). Excess requests are refused at once rather than queued: a queue would hold request threads and
 * still let the backlog grow.
 */
@Component
class SimulationAdmission {

    private final Semaphore running;

    SimulationAdmission(@Value("${quantarun.simulation.max-concurrent-runs:2}") int maxConcurrentRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("quantarun.simulation.max-concurrent-runs must be at least 1");
        }
        this.running = new Semaphore(maxConcurrentRuns);
    }

    boolean tryEnter() {
        return running.tryAcquire();
    }

    void leave() {
        running.release();
    }
}
