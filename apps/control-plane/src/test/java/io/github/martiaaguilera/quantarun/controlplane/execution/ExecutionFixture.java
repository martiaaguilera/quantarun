package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives the real code paths a running system uses: workers register through {@link WorkerRegistry}, jobs are
 * submitted through {@link JobLifecycle} and placed by the real {@link SchedulingCycle}. Only time is faked, by moving
 * {@code lease_expires_at} or {@code available_at} in SQL, so no test sleeps through a lease.
 */
public final class ExecutionFixture {

    public record RegisteredWorker(UUID id, String secret) {
        public String bearer() {
            return "Bearer " + secret;
        }
    }

    private final JdbcClient jdbc;
    private final WorkerRegistry registry;
    private final JobLifecycle lifecycle;
    private final SchedulingCycle cycle;
    private final JsonMapper json;
    private UUID project;

    public ExecutionFixture(
            JdbcClient jdbc, WorkerRegistry registry, JobLifecycle lifecycle, SchedulingCycle cycle, JsonMapper json) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.cycle = cycle;
        this.json = json;
    }

    /** Scheduling sees every runnable job and live worker, so each test starts from an empty fleet and queue. */
    public void reset() {
        jdbc.sql("TRUNCATE scheduler_decisions, job_events, job_attempts, jobs, worker_heartbeats, workers CASCADE")
                .update();
        project = jdbc.sql("INSERT INTO projects (name) VALUES (:n) RETURNING id")
                .param("n", "exec-" + UUID.randomUUID().toString().substring(0, 12))
                .query(UUID.class)
                .single();
    }

    public RegisteredWorker worker(String name, int slots) {
        var response = registry.register(new WorkerProtocol.RegisterRequest(
                name, "test", new WorkerProtocol.Capacity(64_000, 65_536, 0, slots), List.of()));
        return new RegisteredWorker(response.workerId(), response.workerSecret());
    }

    public UUID submit(int maxAttempts) {
        return submit(WorkloadType.DELAY, json.createObjectNode().put("durationMs", 10), maxAttempts);
    }

    public UUID submit(WorkloadType type, ObjectNode payload, int maxAttempts) {
        var submission = new JobSubmission(
                type, payload, 4, new ResourceRequest(500, 256, 0), List.of(), maxAttempts, 60, null, null);
        return lifecycle.submit(project, submission, null).job().id();
    }

    /**
     * Heartbeats every test worker, as live workers do every few seconds. Without it a test that runs longer than the
     * late threshold (7 s) on a slow machine sees its workers turn LATE and correctly receive no more work.
     */
    public void heartbeatAllWorkers() {
        jdbc.sql("UPDATE worker_heartbeats SET last_seen_at = now(), beats = beats + 1")
                .update();
    }

    public int place() {
        heartbeatAllWorkers();
        return cycle.runCycle(SchedulingPolicy.FIFO).placed();
    }

    /** The job's newest attempt. */
    public UUID latestAttempt(UUID jobId) {
        return jdbc.sql("SELECT id FROM job_attempts WHERE job_id = :j ORDER BY attempt_no DESC LIMIT 1")
                .param("j", jobId)
                .query(UUID.class)
                .single();
    }

    public void expireLease(UUID attemptId) {
        jdbc.sql("UPDATE job_attempts SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", attemptId)
                .update();
    }

    /** Skips the retry backoff, so the next cycle can place the job again. */
    public void makeRunnableNow(UUID jobId) {
        jdbc.sql("UPDATE jobs SET available_at = now() WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    public void retire(UUID workerId) {
        jdbc.sql("UPDATE workers SET lifecycle = 'OFFLINE' WHERE id = :id")
                .param("id", workerId)
                .update();
    }

    public String jobStatus(UUID jobId) {
        return string("SELECT status FROM jobs WHERE id = :id", jobId);
    }

    public String attemptStatus(UUID attemptId) {
        return string("SELECT status FROM job_attempts WHERE id = :id", attemptId);
    }

    public long eventCount(UUID jobId, String type) {
        return jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = :id AND type = :type")
                .param("id", jobId)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    public long slotsReserved(UUID workerId) {
        return jdbc.sql("SELECT slots_reserved FROM workers WHERE id = :id")
                .param("id", workerId)
                .query(Long.class)
                .single();
    }

    /** Invariant I2: each worker's reservation equals the sum over its active attempts. Same query as SchedulingCycleTest. */
    public void assertReservationsMatchActiveAttempts() {
        var mismatches = jdbc.sql("""
                        SELECT count(*) FROM workers w
                        LEFT JOIN (
                            SELECT worker_id, sum(cpu_millis) cpu, sum(memory_mib) mem, sum(accelerators) acc,
                                   count(*) slots
                            FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING') GROUP BY worker_id
                        ) a ON a.worker_id = w.id
                        WHERE w.cpu_millis_reserved <> coalesce(a.cpu, 0)
                           OR w.memory_mib_reserved <> coalesce(a.mem, 0)
                           OR w.accelerators_reserved <> coalesce(a.acc, 0)
                           OR w.slots_reserved <> coalesce(a.slots, 0)
                        """).query(Long.class).single();
        assertThat(mismatches)
                .as("workers whose reservations disagree with their active attempts (I2)")
                .isZero();
    }

    private String string(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(String.class).single();
    }
}
