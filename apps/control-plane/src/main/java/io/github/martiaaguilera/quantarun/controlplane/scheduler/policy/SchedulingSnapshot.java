package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything a policy may look at, frozen at one instant. The live scheduler builds it from rows it has locked; the
 * simulator builds it from simulated state. Policies see nothing else: no database, no clock, no randomness.
 */
public record SchedulingSnapshot(Instant now, List<PendingJob> jobs, List<WorkerCandidate> workers) {

    public SchedulingSnapshot {
        jobs = List.copyOf(jobs);
        // Fixed worker order is what makes tie-breaks, and therefore whole plans, reproducible.
        workers = workers.stream()
                .sorted(Comparator.comparing(WorkerCandidate::id))
                .toList();
    }

    public record PendingJob(
            UUID id,
            UUID projectId,
            int priority,
            Instant availableAt,
            @Nullable Instant deadline,
            Demand demand,
            Set<String> requiredLabels) {

        public PendingJob {
            requiredLabels = Set.copyOf(requiredLabels);
        }
    }

    /**
     * @param acceptingWork false for a live worker that must not receive new work (draining, or late on heartbeats);
     *     it still counts when deciding whether a job could <em>ever</em> run.
     * @param notAcceptingReason why {@code acceptingWork} is false, shown in decision records.
     */
    public record WorkerCandidate(
            UUID id,
            String name,
            Set<String> labels,
            Resources capacity,
            Resources free,
            boolean acceptingWork,
            @Nullable String notAcceptingReason) {

        public WorkerCandidate {
            labels = Set.copyOf(labels);
        }
    }
}
