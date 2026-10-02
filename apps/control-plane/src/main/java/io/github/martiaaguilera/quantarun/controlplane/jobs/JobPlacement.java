package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        HIGHEST_PRIORITY_FIRST("priority DESC, available_at, id");

        private final String orderBy;

        WindowOrder(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    private final JdbcClient jdbc;
    private final JobRepository jobs;
    private final JobEventRepository events;

    JobPlacement(JdbcClient jdbc, JobRepository jobs, JobEventRepository events) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.events = events;
    }

    /**
     * Locks up to {@code limit} runnable jobs. SKIP LOCKED lets several scheduler instances work at once: each takes
     * jobs the others are not holding, instead of every cycle queueing behind the first one's locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Job> lockRunnableJobs(int limit, WindowOrder order) {
        return jobs.lockRunnable(limit, order.orderBy);
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
        var attemptId = jdbc.sql("""
                        INSERT INTO job_attempts (job_id, attempt_no, worker_id, cpu_millis, memory_mib, accelerators,
                                                  lease_expires_at)
                        VALUES (:jobId, :attemptNo, :workerId, :cpu, :memory, :accelerators,
                                now() + make_interval(secs => :leaseSeconds))
                        RETURNING id
                        """)
                .param("jobId", job.id())
                .param("attemptNo", attemptNo)
                .param("workerId", workerId)
                .param("cpu", job.resources().cpuMillis())
                .param("memory", job.resources().memoryMib())
                .param("accelerators", job.resources().accelerators())
                .param("leaseSeconds", leaseDuration.toMillis() / 1000.0)
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
        events.append(
                job.id(),
                attemptId,
                JobEventType.SCHEDULED,
                Map.of("workerId", workerId.toString(), "attemptNo", attemptNo, "reason", reason));
        return attemptId;
    }

    /**
     * Records why a job is still waiting. Returns true only when the outcome or reason changed, so the scheduler can
     * write a decision record for changes alone instead of one per cycle.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordWaiting(UUID jobId, String outcome, String reason) {
        return jdbc.sql("""
                        UPDATE jobs SET scheduling_outcome = :outcome, scheduling_reason = :reason
                        WHERE id = :id
                          AND (scheduling_outcome IS DISTINCT FROM :outcome OR scheduling_reason IS DISTINCT FROM :reason)
                        """)
                        .param("outcome", outcome)
                        .param("reason", reason)
                        .param("id", jobId)
                        .update()
                == 1;
    }
}
