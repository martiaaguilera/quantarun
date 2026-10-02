package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import io.github.martiaaguilera.quantarun.controlplane.jobs.AttemptStatus;
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

    public record ClaimedAttempt(
            UUID id, UUID jobId, int attemptNo, String workloadType, ObjectNode payload, int timeoutSeconds) {}

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
                            RETURNING id, job_id, attempt_no
                        )
                        SELECT c.id, c.job_id, c.attempt_no, j.workload_type, j.payload, j.timeout_seconds
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
                        rs.getInt("timeout_seconds")))
                .list();
    }

    /**
     * Renews the leases a live worker still holds. Unclaimed (ASSIGNED) attempts are renewed implicitly, because the
     * worker has not seen them yet. RUNNING ones are renewed only if the worker reports them, so an attempt the worker
     * lost track of still expires. An already-expired lease is never renewed (invariant I10): if the reaper holds the
     * row, this statement waits, re-checks the predicate and skips the now-LOST attempt.
     */
    public List<UUID> renewLeases(UUID workerId, List<UUID> runningAttemptIds, Duration leaseDuration) {
        return jdbc.sql("""
                        UPDATE job_attempts
                        SET lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                            lease_renewals = lease_renewals + 1
                        WHERE worker_id = :workerId
                          AND status IN ('ASSIGNED', 'RUNNING')
                          AND lease_expires_at > now()
                          AND (status = 'ASSIGNED' OR id = ANY(:ids))
                        RETURNING id
                        """)
                .param("workerId", workerId)
                .param("ids", runningAttemptIds.toArray(UUID[]::new))
                .param("leaseSeconds", leaseDuration.toMillis() / 1000.0)
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
    public Optional<LockedAttempt> lock(UUID attemptId) {
        return jdbc.sql("""
                        SELECT id, job_id, attempt_no, worker_id, status, cpu_millis, memory_mib, accelerators
                        FROM job_attempts WHERE id = :id FOR UPDATE
                        """)
                .param("id", attemptId)
                .query((rs, row) -> new LockedAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("job_id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getObject("worker_id", UUID.class),
                        AttemptStatus.valueOf(rs.getString("status")),
                        rs.getInt("cpu_millis"),
                        rs.getInt("memory_mib"),
                        rs.getInt("accelerators")))
                .optional();
    }

    public void finish(
            UUID attemptId,
            AttemptStatus status,
            @Nullable String failureClass,
            @Nullable String failureMessage,
            @Nullable String result,
            String retryDecision) {
        jdbc.sql("""
                        UPDATE job_attempts
                        SET status = :status, finished_at = now(), failure_class = :failureClass,
                            failure_message = :message, result = CAST(:result AS jsonb), retry_decision = :decision
                        WHERE id = :id AND status IN ('ASSIGNED', 'RUNNING')
                        """)
                .param("status", status.name())
                .param("failureClass", failureClass)
                .param("message", failureMessage)
                .param("result", result)
                .param("decision", retryDecision)
                .param("id", attemptId)
                .update();
    }

    /**
     * Expired leases, oldest first. SKIP LOCKED: an attempt locked by a concurrent report or renewal is someone
     * else's to resolve; several reapers can run at once and never handle the same attempt twice.
     */
    public List<UUID> lockExpired(int limit) {
        return jdbc.sql("""
                        SELECT id FROM job_attempts
                        WHERE status IN ('ASSIGNED', 'RUNNING') AND lease_expires_at < now()
                        ORDER BY lease_expires_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """).param("limit", limit).query(UUID.class).list();
    }

    /**
     * While the control plane is down nobody can renew a lease, so after a restart every lease may already look
     * expired. Pushing active leases forward by one full duration means control-plane downtime is never counted
     * against the workers; a worker that really died still expires one lease duration later.
     */
    public int extendActiveLeases(Duration leaseDuration) {
        return jdbc.sql("""
                        UPDATE job_attempts
                        SET lease_expires_at = greatest(lease_expires_at, now() + make_interval(secs => :leaseSeconds))
                        WHERE status IN ('ASSIGNED', 'RUNNING')
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
            tools.jackson.databind.@Nullable JsonNode result) {}

    public List<AttemptView> findByJob(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, attempt_no, worker_id, status, assigned_at, started_at, finished_at,
                               lease_expires_at, lease_renewals, failure_class, failure_message, retry_decision, result
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
                        rs.getString("result") == null ? null : json.readTree(rs.getString("result"))))
                .list();
    }
}
