package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything a policy may look at, frozen at one instant. The live scheduler builds it from rows it has locked; the
 * simulator builds it from simulated state. Policies see nothing else: no database, no clock, no randomness.
 */
public record SchedulingSnapshot(
        Instant now,
        List<PendingJob> jobs,
        List<WorkerCandidate> workers,
        Map<UUID, ProjectState> projects,
        double systemVirtualTime) {

    /** A snapshot without project state: every project has weight 1, no quota and virtual time 0. */
    public SchedulingSnapshot(Instant now, List<PendingJob> jobs, List<WorkerCandidate> workers) {
        this(now, jobs, workers, Map.of(), 0);
    }

    public SchedulingSnapshot {
        jobs = List.copyOf(jobs);
        projects = Map.copyOf(projects);
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

    /** The project's state for a job, or the neutral default when the snapshot carries none. */
    public ProjectState projectOf(PendingJob job) {
        return projects.getOrDefault(job.projectId(), ProjectState.unconstrained(job.projectId()));
    }

    /**
     * @param weight relative share under FAIR_SHARE (1 to 1000).
     * @param virtualTime service received so far, in weighted fleet-seconds (see {@link PlacementPlanner}).
     * @param maxRunningJobs quota on concurrently active attempts; null when unlimited.
     * @param maxAccelerators quota on accelerators held by active attempts; null when unlimited.
     * @param runningJobs active attempts right now.
     * @param acceleratorsInUse accelerators held by those attempts.
     */
    public record ProjectState(
            UUID id,
            String name,
            int weight,
            double virtualTime,
            @Nullable Integer maxRunningJobs,
            @Nullable Integer maxAccelerators,
            int runningJobs,
            int acceleratorsInUse) {

        static ProjectState unconstrained(UUID id) {
            return new ProjectState(id, id.toString().substring(0, 8), 1, 0, null, null, 0, 0);
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
