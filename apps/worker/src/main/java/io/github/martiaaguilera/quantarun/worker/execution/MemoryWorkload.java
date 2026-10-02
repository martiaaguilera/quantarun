package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.Map;

/**
 * Holds {@code mib} MiB of heap for {@code holdMs}, touching every page so the memory is really committed. It makes
 * memory reservations observable; a worker that cannot provide the memory reports RESOURCE_EXHAUSTED, which is
 * retried, possibly on a roomier worker.
 */
final class MemoryWorkload implements Workload {

    static final long MAX_MIB = 1024;
    static final long MAX_HOLD_MS = 600_000;
    private static final int MIB = 1024 * 1024;
    private static final int PAGE = 4096;

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var mib = payload.requireLong("mib", 1, MAX_MIB);
        var holdMs = payload.requireLong("holdMs", 0, MAX_HOLD_MS);
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
            // Catching OOM is normally a mistake. Here the allocation is the workload itself and is released at
            // once by clearing the list, so the worker survives and the attempt is retried somewhere roomier.
            var allocated = chunks.size();
            chunks.clear();
            throw new WorkloadFailure(
                    FailureClass.RESOURCE_EXHAUSTED, "could allocate only " + allocated + " of " + mib + " MiB");
        }
        Thread.sleep(holdMs);
        return Map.of("allocatedMib", chunks.size(), "heldMs", holdMs);
    }
}
