package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import io.github.martiaaguilera.quantarun.controlplane.jobs.Job;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobPlacement;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.DecisionRepository;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Demand;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementPlanner;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Resources;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot;
import io.github.martiaaguilera.quantarun.controlplane.workers.Worker;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerCapacity;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerProperties;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerResources;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One scheduling cycle, as one short transaction (docs/ARCHITECTURE.md §4, §6):
 *
 * <ol>
 *   <li>lock a window of runnable jobs (SKIP LOCKED);
 *   <li>lock every live worker, in id order (jobs before workers, everywhere, so cycles cannot deadlock);
 *   <li>plan with the pure policy over that locked snapshot;
 *   <li>for each placement, insert the attempt, reserve capacity, and move the job to SCHEDULED; for each waiting job,
 *       record its reason if it changed.
 * </ol>
 *
 * Nothing in the cycle performs network I/O, so the locks are held for milliseconds.
 */
@Service
public class SchedulingCycle {

    private static final Logger log = LoggerFactory.getLogger(SchedulingCycle.class);

    public record CycleResult(int considered, int placed, int waiting, int unschedulable) {}

    private final JobPlacement jobs;
    private final WorkerCapacity workers;
    private final DecisionRepository decisions;
    private final JdbcClient jdbc;
    private final SchedulerProperties properties;
    private final WorkerProperties workerProperties;

    SchedulingCycle(
            JobPlacement jobs,
            WorkerCapacity workers,
            DecisionRepository decisions,
            JdbcClient jdbc,
            SchedulerProperties properties,
            WorkerProperties workerProperties) {
        this.jobs = jobs;
        this.workers = workers;
        this.decisions = decisions;
        this.jdbc = jdbc;
        this.properties = properties;
        this.workerProperties = workerProperties;
    }

    /**
     * Invariants I1 (capacity) and I3 (one active attempt): every placement and its reservation commit together.
     *
     * <p>Callers must go through the Spring proxy. An earlier no-argument overload called this method on {@code this},
     * which bypassed {@code @Transactional}; the MANDATORY propagation of the lock-taking methods turned that into an
     * immediate failure instead of unlocked scheduling (docs/ENGINEERING_LOG.md).
     */
    @Transactional
    public CycleResult runCycle(SchedulingPolicy policy) {
        var windowOrder = policy.ordering() == SchedulingPolicy.JobOrdering.PRIORITY
                ? JobPlacement.WindowOrder.HIGHEST_PRIORITY_FIRST
                : JobPlacement.WindowOrder.OLDEST_FIRST;
        var lockedJobs = jobs.lockRunnableJobs(properties.windowSize(), windowOrder);
        if (lockedJobs.isEmpty()) {
            return new CycleResult(0, 0, 0, 0);
        }
        var liveWorkers = workers.lockLiveWorkers();
        // Database time, taken inside the transaction: the same clock that stamps availability and heartbeats.
        var now = jdbc.sql("SELECT now()").query(Instant.class).single();

        var snapshot = new SchedulingSnapshot(
                now,
                lockedJobs.stream().map(SchedulingCycle::toPendingJob).toList(),
                liveWorkers.stream().map(this::toCandidate).toList());
        var plan = PlacementPlanner.planPlacements(snapshot, policy);
        var jobsById = lockedJobs.stream().collect(Collectors.toMap(Job::id, Function.identity()));

        int placed = 0;
        int waiting = 0;
        int unschedulable = 0;
        for (var decision : plan) {
            var job = jobsById.get(decision.job().id());
            var queueWaitMs =
                    Math.max(0, Duration.between(job.availableAt(), now).toMillis());
            switch (decision.outcome()) {
                case PLACED -> {
                    var demand = decision.job().demand();
                    workers.reserve(
                            decision.chosenWorkerId(), demand.cpuMillis(), demand.memoryMib(), demand.accelerators());
                    var attemptId = jobs.assignAttempt(
                            job, decision.chosenWorkerId(), decision.reason(), workerProperties.leaseDuration());
                    decisions.insert(decision, policy, attemptId, queueWaitMs);
                    placed++;
                }
                case WAITING_FOR_CAPACITY, UNSCHEDULABLE -> {
                    if (jobs.recordWaiting(job.id(), decision.outcome().name(), decision.reason())) {
                        decisions.insert(decision, policy, null, queueWaitMs);
                        logWaitingChange(decision);
                    }
                    if (decision.outcome() == PlacementDecision.Outcome.UNSCHEDULABLE) {
                        unschedulable++;
                    } else {
                        waiting++;
                    }
                }
            }
        }
        return new CycleResult(lockedJobs.size(), placed, waiting, unschedulable);
    }

    private static SchedulingSnapshot.PendingJob toPendingJob(Job job) {
        var resources = job.resources();
        return new SchedulingSnapshot.PendingJob(
                job.id(),
                job.projectId(),
                job.priority(),
                job.availableAt(),
                job.deadlineAt(),
                new Demand(resources.cpuMillis(), resources.memoryMib(), resources.accelerators()),
                new HashSet<>(job.requiredLabels()));
    }

    private SchedulingSnapshot.WorkerCandidate toCandidate(Worker worker) {
        var accepting = workers.isAcceptingWork(worker);
        return new SchedulingSnapshot.WorkerCandidate(
                worker.id(),
                worker.name(),
                new HashSet<>(worker.labels()),
                toResources(worker.capacity()),
                free(worker.capacity(), worker.reserved()),
                accepting,
                accepting ? null : workers.notAcceptingReason(worker));
    }

    private static Resources toResources(WorkerResources resources) {
        return new Resources(resources.cpuMillis(), resources.memoryMib(), resources.accelerators(), resources.slots());
    }

    private static Resources free(WorkerResources capacity, WorkerResources reserved) {
        return new Resources(
                capacity.cpuMillis() - reserved.cpuMillis(),
                capacity.memoryMib() - reserved.memoryMib(),
                capacity.accelerators() - reserved.accelerators(),
                capacity.slots() - reserved.slots());
    }

    private static void logWaitingChange(PlacementDecision decision) {
        var event = decision.outcome() == PlacementDecision.Outcome.UNSCHEDULABLE ? log.atWarn() : log.atDebug();
        event.addKeyValue("jobId", decision.job().id())
                .addKeyValue("projectId", decision.job().projectId())
                .addKeyValue("outcome", decision.outcome())
                .log(decision.reason());
    }

    static List<SchedulingPolicy> availablePolicies() {
        return List.of(SchedulingPolicy.values());
    }
}
