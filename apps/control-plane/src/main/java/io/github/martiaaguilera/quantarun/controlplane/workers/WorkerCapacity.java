package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.workers.internal.WorkerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workers side of a scheduling cycle. MANDATORY propagation: the row locks and the reservation only mean
 * something inside the caller's transaction, together with the attempt that uses them.
 */
@Component
public class WorkerCapacity {

    private final WorkerRepository workers;
    private final WorkerProperties properties;

    WorkerCapacity(WorkerRepository workers, WorkerProperties properties) {
        this.workers = workers;
        this.properties = properties;
    }

    /**
     * Locks every live worker, in id order. A plain FOR UPDATE (not SKIP LOCKED) is deliberate: a scheduler that
     * skipped a worker another cycle holds would wrongly conclude the job fits nowhere. Waiting here briefly
     * serialises concurrent cycles on capacity, which is exactly what keeps reservations correct.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Worker> lockLiveWorkers() {
        return workers.lockLive();
    }

    /** Whether this live worker may receive new work: ACTIVE and heartbeating on time. */
    public boolean isAcceptingWork(Worker worker) {
        return worker.lifecycle() == WorkerLifecycle.ACTIVE && worker.health(properties) == WorkerHealth.HEALTHY;
    }

    public String notAcceptingReason(Worker worker) {
        return worker.lifecycle() != WorkerLifecycle.ACTIVE
                ? "worker is " + worker.lifecycle()
                : "heartbeat is " + worker.health(properties);
    }

    /**
     * Adds a reservation. The CHECK constraints on {@code workers} reject anything beyond capacity, so even a bug in
     * the planner fails the transaction instead of persisting an overcommitted worker (invariant I1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID workerId, int cpuMillis, int memoryMib, int accelerators) {
        workers.addReservation(workerId, cpuMillis, memoryMib, accelerators);
    }
}
