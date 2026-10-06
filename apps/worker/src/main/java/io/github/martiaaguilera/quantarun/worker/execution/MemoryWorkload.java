package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds {@code mib} MiB of heap for {@code holdMs}, touching every page so the memory is really committed. It makes
 * memory reservations observable; a worker that cannot provide the memory reports RESOURCE_EXHAUSTED, which is
 * retried, possibly on a roomier worker.
 *
 * <p>All running memory attempts share a budget of half the heap, reserved before anything is allocated. The image
 * runs the JVM with {@code -XX:+ExitOnOutOfMemoryError}, so a real OutOfMemoryError would end the whole worker and
 * every other tenant's attempt on it, before any catch block ran. The budget makes RESOURCE_EXHAUSTED the outcome
 * instead, and leaves the other half of the heap for everything else the worker does.
 */
final class MemoryWorkload implements Workload {

    static final long MAX_MIB = 1024;
    static final long MAX_HOLD_MS = 600_000;
    private static final int MIB = 1024 * 1024;
    private static final int PAGE = 4096;

    private final long budgetMib;
    private final AtomicLong heldMib = new AtomicLong();

    MemoryWorkload() {
        this(Runtime.getRuntime().maxMemory() / MIB / 2);
    }

    MemoryWorkload(long budgetMib) {
        this.budgetMib = budgetMib;
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var mib = payload.requireLong("mib", 1, MAX_MIB);
        var holdMs = payload.requireLong("holdMs", 0, MAX_HOLD_MS);
        if (!tryReserve(mib)) {
            throw new WorkloadFailure(
                    FailureClass.RESOURCE_EXHAUSTED,
                    "needs " + mib + " MiB; " + (budgetMib - heldMib.get()) + " of this worker's " + budgetMib
                            + " MiB are free");
        }
        try {
            return hold(mib, holdMs);
        } finally {
            heldMib.addAndGet(-mib);
        }
    }

    private Map<String, Object> hold(long mib, long holdMs) throws InterruptedException {
        var chunks = new ArrayList<byte[]>((int) mib);
        try {
            for (int i = 0; i < mib; i++) {
                var chunk = new byte[MIB];
                for (int offset = 0; offset < MIB; offset += PAGE) {
                    chunk[offset] = 1;
                }
                chunks.add(chunk);
            }
        } catch (OutOfMemoryError e) {
            // Reached only without ExitOnOutOfMemoryError (tests, a JVM started by hand), and only if the budget was
            // not enough. The allocation is the workload itself and is released at once by clearing the list.
            var allocated = chunks.size();
            chunks.clear();
            throw new WorkloadFailure(
                    FailureClass.RESOURCE_EXHAUSTED, "could allocate only " + allocated + " of " + mib + " MiB");
        }
        Thread.sleep(holdMs);
        return Map.of("allocatedMib", chunks.size(), "heldMs", holdMs);
    }

    /** Reserves {@code mib} of the budget, or nothing; concurrent attempts can never overshoot it together. */
    boolean tryReserve(long mib) {
        long held;
        do {
            held = heldMib.get();
            if (held + mib > budgetMib) {
                return false;
            }
        } while (!heldMib.compareAndSet(held, held + mib));
        return true;
    }

    long heldMib() {
        return heldMib.get();
    }
}
