package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Lets a worker's claim wait for work placed on it instead of the worker polling: a placement raises the signal of
 * the worker it chose, after commit. With polling, an assignment waited up to the 500 ms claim interval, which was half
 * of a lightly loaded job's time to start and, under a backlog, capped throughput (BENCHMARKS.md).
 *
 * <p>A waiter registers before it queries, so a placement that commits between the query and the wait is not missed.
 * In-process only; a waiter also wakes on its own at the caller's recheck interval, so work placed by another
 * control-plane instance is found no later than with polling. At most one waiter per worker exists (its intake loop),
 * so the map is bounded by the live fleet.
 */
@Component
public class AssignmentSignal {

    private final ConcurrentMap<UUID, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /** Registers interest in the next placement on {@code workerId}; call before querying for its assignments. */
    public Waiter register(UUID workerId) {
        return new Waiter(workerId, waiters.computeIfAbsent(workerId, id -> new CompletableFuture<>()));
    }

    void raiseAfterCommit(UUID workerId) {
        AfterCommit.run(() -> {
            var waiter = waiters.remove(workerId);
            if (waiter != null) {
                waiter.complete(null);
            }
        });
    }

    public final class Waiter {

        private final UUID workerId;
        private final CompletableFuture<Void> placed;

        private Waiter(UUID workerId, CompletableFuture<Void> placed) {
            this.workerId = workerId;
            this.placed = placed;
        }

        /** @return true when something was placed on the worker, false when {@code timeout} passed first. */
        public boolean await(Duration timeout) throws InterruptedException {
            try {
                placed.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                return true;
            } catch (TimeoutException e) {
                return false;
            } catch (ExecutionException e) {
                throw new IllegalStateException("an assignment signal never completes exceptionally", e);
            } finally {
                waiters.remove(workerId, placed);
            }
        }
    }
}
