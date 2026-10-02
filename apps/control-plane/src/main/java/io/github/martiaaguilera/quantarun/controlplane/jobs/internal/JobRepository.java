package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import io.github.martiaaguilera.quantarun.controlplane.jobs.Job;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class JobRepository {

    private static final String COLUMNS = """
            id, project_id, workload_type, payload, status, priority, cpu_millis, memory_mib, accelerators,
            required_labels, max_attempts, attempt_count, budget_start, revive_count, timeout_seconds, available_at, deadline_at,
            idempotency_key, cancel_requested_at, scheduling_outcome, scheduling_reason, created_at, updated_at,
            finished_at
            """;

    public record ListFilter(
            @Nullable UUID projectId,
            @Nullable JobStatus status,
            @Nullable UUID before,
            int limit) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<Job> jobMapper;

    JobRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.jobMapper = this::mapJob;
    }

    /**
     * Inserts the job unless the project already used this idempotency key. Concurrent duplicates serialize on the
     * unique index: the losers wait for the winner's transaction and then skip, instead of failing and aborting their
     * own transaction the way a plain INSERT plus a caught unique violation would. Returns empty when the key existed.
     */
    public Optional<Job> insertIfAbsent(
            UUID projectId, JobSubmission submission, @Nullable String idempotencyKey, byte @Nullable [] requestHash) {
        return jdbc.sql("""
                        INSERT INTO jobs (project_id, workload_type, payload, status, priority, cpu_millis,
                                          memory_mib, accelerators, required_labels, max_attempts, timeout_seconds,
                                          available_at, deadline_at, idempotency_key, request_hash)
                        VALUES (:projectId, :workloadType, CAST(:payload AS jsonb), 'QUEUED', :priority,
                                :cpuMillis, :memoryMib, :accelerators, :labels, :maxAttempts, :timeoutSeconds,
                                coalesce(:notBefore, now()), :deadline, :idempotencyKey, :requestHash)
                        ON CONFLICT (project_id, idempotency_key) DO NOTHING
                        RETURNING
                        """ + COLUMNS)
                .param("projectId", projectId)
                .param("workloadType", submission.workloadType().wireName())
                .param("payload", json.writeValueAsString(submission.payload()))
                .param("priority", submission.priority())
                .param("cpuMillis", submission.resources().cpuMillis())
                .param("memoryMib", submission.resources().memoryMib())
                .param("accelerators", submission.resources().accelerators())
                .param("labels", submission.requiredLabels().toArray(String[]::new))
                .param("maxAttempts", submission.maxAttempts())
                .param("timeoutSeconds", submission.timeoutSeconds())
                .param("notBefore", timestamp(submission.notBefore()))
                .param("deadline", timestamp(submission.deadline()))
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .query(jobMapper)
                .optional();
    }

    public record IdempotencyRecord(Job job, byte[] requestHash) {}

    public Optional<IdempotencyRecord> findByIdempotencyKey(UUID projectId, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + ", request_hash FROM jobs"
                        + " WHERE project_id = :projectId AND idempotency_key = :key")
                .param("projectId", projectId)
                .param("key", idempotencyKey)
                .query((rs, row) -> new IdempotencyRecord(mapJob(rs, row), rs.getBytes("request_hash")))
                .optional();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM jobs WHERE id = :id")
                .param("id", id)
                .query(jobMapper)
                .optional();
    }

    /**
     * Locks runnable jobs for one scheduling cycle. {@code orderBy} comes from a fixed enum, never from input, so
     * concatenating it is not an injection risk.
     */
    public List<Job> lockRunnable(int limit, String orderBy) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM jobs"
                        + " WHERE status IN ('QUEUED', 'RETRY_WAIT') AND available_at <= now()"
                        + " ORDER BY " + orderBy + " LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query(jobMapper)
                .list();
    }

    /** Row lock on a job; callers that also lock its attempt must have locked the attempt first. */
    public Optional<Job> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM jobs WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(jobMapper)
                .optional();
    }

    /**
     * Moves a locked job on after one of its attempts ended. A retry becomes runnable again after {@code retryDelay},
     * measured on the database clock, and its previous scheduling verdict is cleared.
     */
    public void applyAttemptOutcome(UUID id, JobStatus current, JobStatus next, @Nullable Duration retryDelay) {
        current.requireTransitionTo(next);
        var updated = jdbc.sql("""
                        UPDATE jobs
                        SET status = :next,
                            updated_at = now(),
                            finished_at = CASE WHEN :final THEN now() END,
                            available_at = CASE WHEN :next = 'RETRY_WAIT'
                                                THEN now() + make_interval(secs => :delaySeconds)
                                                ELSE available_at END,
                            scheduling_outcome = CASE WHEN :next = 'RETRY_WAIT' THEN NULL ELSE scheduling_outcome END,
                            scheduling_reason = CASE WHEN :next = 'RETRY_WAIT' THEN NULL ELSE scheduling_reason END
                        WHERE id = :id AND status = :current
                        """)
                .param("next", next.name())
                .param("final", next.isFinal())
                .param("delaySeconds", retryDelay == null ? 0.0 : retryDelay.toMillis() / 1000.0)
                .param("id", id)
                .param("current", current.name())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Job " + id + " was not " + current + " while its row was locked");
        }
    }

    /** Keyset pagination on the time-ordered id: stable under concurrent inserts, no OFFSET scans. */
    public List<Job> list(ListFilter filter) {
        var sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM jobs WHERE true");
        // Conditions are appended only when present, so each filter combination gets a plan that uses its index
        // instead of one generic plan full of "param IS NULL OR ..." branches.
        if (filter.projectId() != null) {
            sql.append(" AND project_id = :projectId");
        }
        if (filter.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filter.before() != null) {
            sql.append(" AND id < :before");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString()).param("limit", filter.limit());
        if (filter.projectId() != null) {
            statement = statement.param("projectId", filter.projectId());
        }
        if (filter.status() != null) {
            statement = statement.param("status", filter.status().name());
        }
        if (filter.before() != null) {
            statement = statement.param("before", filter.before());
        }
        return statement.query(jobMapper).list();
    }

    /**
     * Conditional transition: succeeds only if the job is still in one of {@code expected}. Every pair must be legal
     * in {@link JobStatus}; the check runs before the SQL so an illegal transition can never be attempted.
     */
    public Optional<Job> transition(UUID id, List<JobStatus> expected, JobStatus next) {
        expected.forEach(from -> from.requireTransitionTo(next));
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = :next,
                            updated_at = now(),
                            finished_at = CASE WHEN :final THEN now() END,
                            cancel_requested_at = CASE WHEN :next = 'CANCELLED'
                                                       THEN coalesce(cancel_requested_at, now())
                                                       ELSE cancel_requested_at END
                        WHERE id = :id AND status = ANY(:expected)
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("next", next.name())
                .param("final", next.isFinal())
                .param("expected", expected.stream().map(Enum::name).toArray(String[]::new))
                .query(jobMapper)
                .optional();
    }

    /**
     * DEAD to QUEUED with a fresh attempt budget, measured from the attempts already made (invariants I9, I13). One
     * statement, conditional on DEAD and on the revive cap, so of several concurrent revives exactly one matches.
     */
    public Optional<Job> revive(UUID id, int maxRevives) {
        JobStatus.DEAD.requireTransitionTo(JobStatus.QUEUED);
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'QUEUED', budget_start = attempt_count, revive_count = revive_count + 1,
                            available_at = now(), finished_at = NULL, scheduling_outcome = NULL,
                            scheduling_reason = NULL, updated_at = now()
                        WHERE id = :id AND status = 'DEAD' AND revive_count < :maxRevives
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("maxRevives", maxRevives)
                .query(jobMapper)
                .optional();
    }

    /**
     * Marks a job with an active attempt for cooperative cancellation. The worker learns about it on its next
     * heartbeat; the status itself only changes when the attempt ends. Matches only the first request, so exactly
     * one concurrent caller sees a row and records the CANCEL_REQUESTED event.
     */
    public Optional<Job> requestCancellation(UUID id) {
        return jdbc.sql("""
                        UPDATE jobs
                        SET cancel_requested_at = now(), updated_at = now()
                        WHERE id = :id AND status IN ('SCHEDULED', 'RUNNING') AND cancel_requested_at IS NULL
                        RETURNING
                        """ + COLUMNS).param("id", id).query(jobMapper).optional();
    }

    private Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        var workloadType = rs.getString("workload_type");
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                WorkloadType.fromWireName(workloadType)
                        .orElseThrow(() -> new IllegalStateException("Unknown workload type in DB: " + workloadType)),
                (ObjectNode) json.readTree(rs.getString("payload")),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("priority"),
                new ResourceRequest(rs.getInt("cpu_millis"), rs.getInt("memory_mib"), rs.getInt("accelerators")),
                Arrays.asList((String[]) rs.getArray("required_labels").getArray()),
                rs.getInt("max_attempts"),
                rs.getInt("attempt_count"),
                rs.getInt("budget_start"),
                rs.getInt("revive_count"),
                rs.getInt("timeout_seconds"),
                instant(rs, "available_at"),
                instant(rs, "deadline_at"),
                rs.getString("idempotency_key"),
                instant(rs, "cancel_requested_at"),
                rs.getString("scheduling_outcome"),
                rs.getString("scheduling_reason"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "finished_at"));
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable Timestamp timestamp(@Nullable Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
