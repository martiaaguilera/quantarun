package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The jobs side of a scheduling cycle. Every method requires the caller's transaction (MANDATORY): the locks taken
 * here are only meaningful if the reservation on the worker and the attempt row commit together with them.
 */
@Component
public class JobPlacement {

    /** Which runnable jobs enter the scheduler's window when more are waiting than one cycle considers. */
    public enum WindowOrder {
        OLDEST_FIRST("available_at, id"),
        HIGHEST_PRIORITY_FIRST("priority DESC, available_at, id"),
        EARLIEST_DEADLINE_FIRST("deadline_at NULLS LAST, priority DESC, available_at, id"),
        /**
         * Round-robin over projects: every backlogged project's oldest jobs, taken rank by rank. With an oldest-first
         * window, a project that queued 10,000 jobs would fill every window and the fair-share policy would never even
         * see the other projects' jobs.
         */
        PER_PROJECT_ROUND_ROBIN(null);

        private final @Nullable String orderBy;

        WindowOrder(@Nullable String orderBy) {
            this.orderBy = orderBy;
        }
    }

    private final JdbcClient jdbc;
    private final AssignmentSignal assignmentSignal;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final JobTracing tracing;

    JobPlacement(
            JdbcClient jdbc,
            JobRepository jobs,
            JobEventRepository events,
            JobTracing tracing,
            AssignmentSignal assignmentSignal) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.events = events;
        this.tracing = tracing;
        this.assignmentSignal = assignmentSignal;
    }

    /**
     * Locks up to {@code limit} runnable jobs. SKIP LOCKED lets several scheduler instances work at once: each takes
     * jobs the others are not holding, instead of every cycle queueing behind the first one's locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Job> lockRunnableJobs(int limit, WindowOrder order) {
        return order == WindowOrder.PER_PROJECT_ROUND_ROBIN
                ? jobs.lockRunnableRoundRobin(limit)
                : jobs.lockRunnable(limit, order.orderBy);
    }

    public record ProjectUsage(int runningJobs, int acceleratorsInUse) {}

    /**
     * Active attempts per project, for quota checks. MANDATORY and read after the cycle has locked the workers: every
     * other cycle that places work holds those same locks until it commits, so this count cannot miss a concurrent
     * placement, and a quota can never be overrun by two cycles at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, ProjectUsage> activeUsage(Collection<UUID> projectIds) {
        var usage = new HashMap<UUID, ProjectUsage>();
        jdbc.sql("""
                        SELECT j.project_id, count(*) AS running, coalesce(sum(a.accelerators), 0) AS accelerators
                        FROM job_attempts a JOIN jobs j ON j.id = a.job_id
                        WHERE a.status IN ('ASSIGNED', 'RUNNING') AND j.project_id = ANY(:ids)
                        GROUP BY j.project_id
                        """)
                .param("ids", projectIds.toArray(UUID[]::new))
                .query((rs, row) -> usage.put(
                        rs.getObject("project_id", UUID.class),
                        new ProjectUsage(rs.getInt("running"), rs.getInt("accelerators"))))
                .list();
        return usage;
    }

    /**
     * Creates attempt N+1 on the chosen worker and moves the job to SCHEDULED. The attempt's lease starts now: if
     * the worker never claims it, lease expiry recovers the job like any other lost attempt.
     *
     * @return the new attempt's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID assignAttempt(Job job, UUID workerId, String reason, Duration leaseDuration) {
        job.status().requireTransitionTo(JobStatus.SCHEDULED);
        var attemptNo = job.attemptCount() + 1;
        var traceParent = tracing.placement(job, attemptNo, workerId, reason);
        var attemptId = jdbc.sql("""
                        INSERT INTO job_attempts (job_id, attempt_no, worker_id, cpu_millis, memory_mib, accelerators,
                                                  lease_expires_at, trace_parent)
                        VALUES (:jobId, :attemptNo, :workerId, :cpu, :memory, :accelerators,
                                now() + make_interval(secs => :leaseSeconds), :traceParent)
                        RETURNING id
                        """)
                .param("jobId", job.id())
                .param("attemptNo", attemptNo)
                .param("workerId", workerId)
                .param("cpu", job.resources().cpuMillis())
                .param("memory", job.resources().memoryMib())
                .param("accelerators", job.resources().accelerators())
                .param("leaseSeconds", leaseDuration.toMillis() / 1000.0)
                .param("traceParent", traceParent)
                .query(UUID.class)
                .single();
        var updated =
                jdbc.sql("""
                        UPDATE jobs
                        SET status = 'SCHEDULED', attempt_count = attempt_count + 1, scheduling_outcome = 'PLACED',
                            scheduling_reason = :reason, updated_at = now()
                        WHERE id = :id AND status IN ('QUEUED', 'RETRY_WAIT')
                        """).param("reason", reason).param("id", job.id()).update();
        if (updated != 1) {
            // The row is locked by this transaction, so its status cannot have changed underneath us.
            throw new IllegalStateException("Job " + job.id() + " left the runnable states while locked");
        }
        assignmentSignal.raiseAfterCommit(workerId);
        events.append(
                job.id(),
                attemptId,
                JobEventType.SCHEDULED,
                Map.of("workerId", workerId.toString(), "attemptNo", attemptNo, "reason", reason));
        return attemptId;
    }

    /** Why a job is still waiting after a cycle. */
    public record Waiting(UUID jobId, String outcome, String reason) {}

    /**
     * Records why jobs are still waiting, in one statement. Returns the jobs whose outcome or reason changed, so the
     * scheduler writes a decision record for changes alone instead of one per cycle. One statement, not one per job:
     * a cycle over a 200-job window sent about 190 of these while holding every live worker's row lock, and reports
     * releasing capacity queued behind those locks (BENCHMARKS.md). The rows are already locked by this cycle's window.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> recordWaiting(List<Waiting> waiting) {
        if (waiting.isEmpty()) {
            return Set.of();
        }
        var ids = new String[waiting.size()];
        var outcomes = new String[waiting.size()];
        var reasons = new String[waiting.size()];
        for (int i = 0; i < waiting.size(); i++) {
            ids[i] = waiting.get(i).jobId().toString();
            outcomes[i] = waiting.get(i).outcome();
            reasons[i] = waiting.get(i).reason();
        }
        return new HashSet<>(jdbc.sql("""
                        UPDATE jobs j SET scheduling_outcome = w.outcome, scheduling_reason = w.reason
                        FROM unnest(CAST(:ids AS uuid[]), CAST(:outcomes AS text[]), CAST(:reasons AS text[]))
                             AS w(id, outcome, reason)
                        WHERE j.id = w.id
                          AND (j.scheduling_outcome IS DISTINCT FROM w.outcome
                               OR j.scheduling_reason IS DISTINCT FROM w.reason)
                        RETURNING j.id
                        """)
                .param("ids", ids)
                .param("outcomes", outcomes)
                .param("reasons", reasons)
                .query(UUID.class)
                .list());
    }
}
