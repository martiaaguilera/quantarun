package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import io.github.martiaaguilera.quantarun.controlplane.jobs.AttemptStatus;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkerAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class AttemptRepository {

    public record LockedAttempt(
            UUID id,
            UUID jobId,
            int attemptNo,
            UUID workerId,
            AttemptStatus status,
            int cpuMillis,
            int memoryMib,
            int accelerators) {}

    /** @param queueWait from the job becoming runnable to this claim, on the database clock. */
    public record ClaimedAttempt(
            UUID id,
            UUID jobId,
            int attemptNo,
            String workloadType,
            ObjectNode payload,
            int timeoutSeconds,
            @Nullable String traceParent,
            Duration queueWait) {}

    /** @param startedAt null when the attempt ended before a worker claimed it. */
    public record Ended(java.time.@Nullable Instant startedAt, java.time.Instant finishedAt) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AttemptRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Starts this worker's assigned attempts. An attempt whose lease already expired is left to the reaper: starting
     * it now would race the recovery that may already be placing the job elsewhere.
     */
    public List<ClaimedAttempt> claimAssigned(UUID workerId, int limit) {
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE job_attempts SET status = 'RUNNING', started_at = now()
                            WHERE id IN (
                                SELECT id FROM job_attempts
                                WHERE worker_id = :workerId AND status = 'ASSIGNED' AND lease_expires_at > now()
                                ORDER BY assigned_at
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED)
                            RETURNING id, job_id, attempt_no, trace_parent
                        )
                        SELECT c.id, c.job_id, c.attempt_no, j.workload_type, j.payload, j.timeout_seconds,
                               c.trace_parent,
                               greatest(0, extract(epoch FROM now() - j.available_at) * 1000)::bigint AS queue_wait_ms
                        FROM claimed c JOIN jobs j ON j.id = c.job_id
                        ORDER BY c.attempt_no, c.id
                        """)
                .param("workerId", workerId)
                .param("limit", limit)
                .query((rs, row) -> new ClaimedAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("job_id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getString("workload_type"),
                        (ObjectNode) json.readTree(rs.getString("payload")),
                        rs.getInt("timeout_seconds"),
                        rs.getString("trace_parent"),
                        Duration.ofMillis(rs.getLong("queue_wait_ms"))))
                .list();
    }

    /**
     * Renews the leases a live worker still holds. Unclaimed (ASSIGNED) attempts are renewed implicitly, because the
     * worker has not seen them yet, but only for {@code claimTimeout} after placement: a worker that heartbeats yet never
     * claims would otherwise hold its assignments forever. RUNNING ones are renewed only if the worker reports them, so an attempt the worker
     * lost track of still expires. An already-expired lease is never renewed (invariant I10): if the reaper holds the
     * row, this statement waits, re-checks the predicate and skips the now-LOST attempt.
     *
     * <p>The rows are locked in id order before they are updated. A plain multi-row UPDATE locks rows in whatever
     * order the plan visits them, so two overlapping heartbeats of one worker (a retry after a client timeout, or two
     * control-plane instances) deadlocked in {@code LeaseRecoveryTest}; a fixed order makes them queue instead.
     */
    public List<UUID> renewLeases(
            UUID workerId, List<UUID> runningAttemptIds, Duration leaseDuration, Duration claimTimeout) {
        return jdbc.sql("""
                        WITH renewable AS (
                            SELECT id FROM job_attempts
                            WHERE worker_id = :workerId
                              AND status IN ('ASSIGNED', 'RUNNING')
                              AND lease_expires_at > now()
                              AND ((status = 'ASSIGNED' AND assigned_at > now() - make_interval(secs => :claimSeconds))
                                   OR id = ANY(:ids))
                            ORDER BY id
                            FOR UPDATE
                        )
                        UPDATE job_attempts a
                        SET lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                            lease_renewals = a.lease_renewals + 1
                        FROM renewable r
                        WHERE a.id = r.id
                          AND a.status IN ('ASSIGNED', 'RUNNING')
                          AND a.lease_expires_at > now()
                        RETURNING a.id
                        """)
                .param("workerId", workerId)
                .param("ids", runningAttemptIds.toArray(UUID[]::new))
                .param("leaseSeconds", leaseDuration.toMillis() / 1000.0)
                .param("claimSeconds", claimTimeout.toMillis() / 1000.0)
                .query(UUID.class)
                .list();
    }

    /** Running attempts of this worker whose jobs have a pending cancel request. */
    public List<UUID> findCancelRequested(UUID workerId, List<UUID> attemptIds) {
        return jdbc.sql("""
                        SELECT a.id FROM job_attempts a JOIN jobs j ON j.id = a.job_id
                        WHERE a.worker_id = :workerId AND a.status = 'RUNNING' AND a.id = ANY(:ids)
                          AND j.cancel_requested_at IS NOT NULL
                        """)
                .param("workerId", workerId)
                .param("ids", attemptIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list();
    }

    /** Row lock on one attempt. Every path that ends an attempt goes through here first (attempt, job, worker order). */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    public Optional<LockedAttempt> lock(UUID attemptId) {
        return jdbc.sql("""
                        SELECT id, job_id, attempt_no, worker_id, status, cpu_millis, memory_mib, accelerators
                        FROM job_attempts WHERE id = :id FOR UPDATE
                        """).param("id", attemptId).query(this::mapLocked).optional();
    }

    /**
     * Whether the attempt's lease is still held, by the database clock, as claim and renewal judge it. Asked after the
     * row is locked, so no renewal or recovery can change the answer before the caller's transaction ends.
     */
    public boolean leaseHeld(UUID attemptId) {
        return jdbc.sql("SELECT lease_expires_at > now() FROM job_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(Boolean.class)
                .single();
    }

    private LockedAttempt mapLocked(ResultSet rs, int row) throws SQLException {
        return new LockedAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getObject("worker_id", UUID.class),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getInt("cpu_millis"),
                rs.getInt("memory_mib"),
                rs.getInt("accelerators"));
    }

    public Ended finish(
            UUID attemptId,
            AttemptStatus status,
            @Nullable String failureClass,
            @Nullable String failureMessage,
            @Nullable String result,
            String retryDecision) {
        return jdbc.sql("""
                        UPDATE job_attempts
                        SET status = :status, finished_at = now(), failure_class = :failureClass,
                            failure_message = :message, result = CAST(:result AS jsonb), retry_decision = :decision
                        WHERE id = :id AND status IN ('ASSIGNED', 'RUNNING')
                        RETURNING started_at, finished_at
                        """)
                .param("status", status.name())
                .param("failureClass", failureClass)
                .param("message", failureMessage)
                .param("result", result)
                .param("decision", retryDecision)
                .param("id", attemptId)
                .query((rs, row) ->
                        new Ended(JobRepository.instant(rs, "started_at"), JobRepository.instant(rs, "finished_at")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Attempt " + attemptId + " was not active when finished"));
    }

    @Nullable
    public String traceParent(UUID attemptId) {
        return jdbc.sql("SELECT trace_parent FROM job_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * Locks the oldest expired leases. SKIP LOCKED: an attempt locked by a concurrent report or renewal is someone
     * else's to resolve; several reapers can run at once and never handle the same attempt twice.
     *
     * <p>The batch is returned in worker id order, not expiry order. Ending each attempt locks its worker row and
     * holds it until commit, so a batch spanning several workers takes several worker locks; taking them in the same
     * ascending order as the scheduler is what keeps two reapers (or a reaper and a scheduler) from deadlocking. The
     * sort happens in SQL so it matches PostgreSQL's uuid ordering, which differs from {@link UUID#compareTo}.
     */
    public List<LockedAttempt> lockExpired(int limit) {
        return jdbc.sql("""
                        SELECT id, job_id, attempt_no, worker_id, status, cpu_millis, memory_mib, accelerators
                        FROM (
                            SELECT * FROM job_attempts
                            WHERE status IN ('ASSIGNED', 'RUNNING') AND lease_expires_at < now()
                            ORDER BY lease_expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        ) expired
                        ORDER BY worker_id, id
                        """).param("limit", limit).query(this::mapLocked).list();
    }

    /**
     * While the control plane is down nobody can renew a lease, so after a restart every lease may already look
     * expired. Pushing active leases forward by one full duration means control-plane downtime is never counted
     * against the workers; a worker that really died still expires one lease duration later.
     */
    public int extendActiveLeases(Duration leaseDuration) {
        // Locks in id order, like lease renewal, so the two can never deadlock (ENGINEERING_LOG, 2026-10-02).
        return jdbc.sql("""
                        WITH active AS (
                            SELECT id FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING') ORDER BY id FOR UPDATE
                        )
                        UPDATE job_attempts a
                        SET lease_expires_at = greatest(a.lease_expires_at, now() + make_interval(secs => :leaseSeconds))
                        FROM active
                        WHERE a.id = active.id AND a.status IN ('ASSIGNED', 'RUNNING')
                        """)
                .param("leaseSeconds", leaseDuration.toMillis() / 1000.0)
                .update();
    }

    public record AttemptView(
            UUID id,
            int attemptNo,
            UUID workerId,
            String status,
            java.time.Instant assignedAt,
            java.time.@Nullable Instant startedAt,
            java.time.@Nullable Instant finishedAt,
            java.time.Instant leaseExpiresAt,
            int leaseRenewals,
            @Nullable String failureClass,
            @Nullable String failureMessage,
            @Nullable String retryDecision,
            tools.jackson.databind.@Nullable JsonNode result,
            @Nullable String traceParent) {}

    /**
     * Attempts a worker held at some point during [from, to]: assigned by {@code to} and not finished before
     * {@code from}. Finished attempts are not indexed by worker, so this scans {@code job_attempts}: acceptable for an
     * operator opening one chaos timeline, and the reason it must never join a hot path.
     */
    public List<WorkerAttempt> findOnWorkerDuring(
            UUID workerId, java.time.Instant from, java.time.Instant to, int limit) {
        return jdbc.sql("""
                        SELECT id, job_id, attempt_no, status, assigned_at, finished_at, failure_class
                        FROM job_attempts
                        WHERE worker_id = :workerId AND assigned_at <= :to
                          AND (finished_at IS NULL OR finished_at >= :from)
                        ORDER BY assigned_at, id
                        LIMIT :limit
                        """)
                .param("workerId", workerId)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .param("limit", limit)
                .query((rs, row) -> new WorkerAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("job_id", UUID.class),
                        rs.getInt("attempt_no"),
                        AttemptStatus.valueOf(rs.getString("status")),
                        JobRepository.instant(rs, "assigned_at"),
                        JobRepository.instant(rs, "finished_at"),
                        rs.getString("failure_class")))
                .list();
    }

    public List<AttemptView> findByJob(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, attempt_no, worker_id, status, assigned_at, started_at, finished_at,
                               lease_expires_at, lease_renewals, failure_class, failure_message, retry_decision, result,
                               trace_parent
                        FROM job_attempts WHERE job_id = :jobId ORDER BY attempt_no
                        """)
                .param("jobId", jobId)
                .query((rs, row) -> new AttemptView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getObject("worker_id", UUID.class),
                        rs.getString("status"),
                        JobRepository.instant(rs, "assigned_at"),
                        JobRepository.instant(rs, "started_at"),
                        JobRepository.instant(rs, "finished_at"),
                        JobRepository.instant(rs, "lease_expires_at"),
                        rs.getInt("lease_renewals"),
                        rs.getString("failure_class"),
                        rs.getString("failure_message"),
                        rs.getString("retry_decision"),
                        rs.getString("result") == null ? null : json.readTree(rs.getString("result")),
                        rs.getString("trace_parent")))
                .list();
    }
}
