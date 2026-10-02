package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Demand;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Resources;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A complete, replayable workload: the fleet, the projects, every job with its arrival and behaviour, and the faults.
 * Generated from a scenario and a seed; the same pair always yields an identical trace. All times are milliseconds from
 * the start of the simulation.
 */
public record Trace(
        List<TraceProject> projects, List<TraceWorker> workers, List<TraceJob> jobs, List<WorkerOutage> outages) {

    public Trace {
        projects = List.copyOf(projects);
        workers = List.copyOf(workers);
        jobs = List.copyOf(jobs);
        outages = List.copyOf(outages);
    }

    public record TraceProject(UUID id, String name, int weight) {}

    public record TraceWorker(UUID id, String name, Set<String> labels, Resources capacity) {

        public TraceWorker {
            labels = Set.copyOf(labels);
        }
    }

    /**
     * @param durationMs how long one attempt runs when it succeeds.
     * @param deadlineMs absolute deadline, or null.
     * @param failures what happens on given attempt numbers (1-based); an attempt not listed succeeds.
     */
    public record TraceJob(
            UUID id,
            UUID projectId,
            long arrivalMs,
            Demand demand,
            Set<String> requiredLabels,
            int priority,
            @Nullable Long deadlineMs,
            long durationMs,
            int maxAttempts,
            Map<Integer, Failure> failures) {

        public TraceJob {
            requiredLabels = Set.copyOf(requiredLabels);
            failures = Map.copyOf(failures);
        }
    }

    /**
     * @param afterMs how long the attempt runs before it fails.
     * @param retryAfterMs a provider's Retry-After, for RATE_LIMITED.
     */
    public record Failure(
            FailureClass failureClass,
            long afterMs,
            @Nullable Long retryAfterMs) {}

    /** The worker disappears at {@code downAtMs} (its leases then expire) and is back, empty, at {@code upAtMs}. */
    public record WorkerOutage(UUID workerId, long downAtMs, long upAtMs) {}
}
