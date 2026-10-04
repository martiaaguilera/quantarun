package io.github.martiaaguilera.quantarun.controlplane.chaos.internal;

import io.github.martiaaguilera.quantarun.controlplane.chaos.ChaosExperiment;
import io.github.martiaaguilera.quantarun.controlplane.chaos.ChaosStatus;
import io.github.martiaaguilera.quantarun.controlplane.chaos.FaultParameters;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChaosExperimentRepository {

    private static final String COLUMNS = """
            id, fault, worker_id, job_id, delay_ms, duration_ms, fault_count, retry_after_ms, latency_ms, status,
            created_at, deliver_by, delivered_at, ended_at""";

    private final JdbcClient jdbc;

    ChaosExperimentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the experiment unless the worker already has {@code maxPending} deliverable ones queued. The count and
     * the insert are serialised per worker by a transaction-scoped advisory lock: two concurrent requests would
     * otherwise both count below the limit. An advisory lock rather than the worker row's lock keeps this away from the
     * scheduler, which holds worker rows while it reserves capacity.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChaosExperiment> insertIfBelowLimit(
            ChaosFault fault,
            UUID workerId,
            @Nullable UUID jobId,
            FaultParameters parameters,
            Duration deliveryWindow,
            int maxPending) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "chaos:" + workerId)
                .query()
                .singleValue();
        var pending =
                jdbc.sql("""
                        SELECT count(*) FROM chaos_experiments
                        WHERE worker_id = :workerId AND status = 'PENDING' AND deliver_by > now()
                        """).param("workerId", workerId).query(Long.class).single();
        if (pending >= maxPending) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("""
                        INSERT INTO chaos_experiments (fault, worker_id, job_id, delay_ms, duration_ms, fault_count,
                                                       retry_after_ms, latency_ms, deliver_by)
                        VALUES (:fault, :workerId, :jobId, :delay, :duration, :count, :retryAfter, :latency,
                                now() + make_interval(secs => :windowSeconds))
                        RETURNING
                        """ + COLUMNS)
                .param("fault", fault.name())
                .param("workerId", workerId)
                .param("jobId", jobId)
                .param("delay", parameters.delayMs())
                .param("duration", parameters.durationMs())
                .param("count", parameters.count())
                .param("retryAfter", parameters.retryAfterMs())
                .param("latency", parameters.latencyMs())
                .param("windowSeconds", deliveryWindow.toMillis() / 1000.0)
                .query(this::map)
                .single());
    }

    /**
     * Hands a worker its deliverable experiments, each exactly once: concurrent heartbeats of one worker serialise on
     * the row locks, and the loser re-checks {@code status = 'PENDING'} and skips the row.
     */
    public List<ChaosExperiment> deliver(UUID workerId) {
        return jdbc.sql("""
                        UPDATE chaos_experiments SET status = 'DELIVERED', delivered_at = now()
                        WHERE worker_id = :workerId AND status = 'PENDING' AND deliver_by > now()
                        RETURNING
                        """ + COLUMNS)
                .param("workerId", workerId)
                .query(this::map)
                .list();
    }

    /** @return how many undelivered experiments passed their delivery deadline and were expired */
    public int expireOverdue() {
        return jdbc.sql("""
                        UPDATE chaos_experiments SET status = 'EXPIRED', ended_at = now()
                        WHERE status = 'PENDING' AND deliver_by <= now()
                        """).update();
    }

    /** @return the experiment after the cancellation, or empty if it was no longer pending */
    public Optional<ChaosExperiment> cancel(UUID id) {
        return jdbc.sql("""
                        UPDATE chaos_experiments SET status = 'CANCELLED', ended_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        RETURNING
                        """ + COLUMNS).param("id", id).query(this::map).optional();
    }

    public Optional<ChaosExperiment> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chaos_experiments WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<ChaosExperiment> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chaos_experiments ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    private ChaosExperiment map(ResultSet rs, int row) throws SQLException {
        return new ChaosExperiment(
                rs.getObject("id", UUID.class),
                ChaosFault.valueOf(rs.getString("fault")),
                rs.getObject("worker_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                new FaultParameters(
                        rs.getInt("delay_ms"),
                        rs.getInt("duration_ms"),
                        rs.getInt("fault_count"),
                        rs.getInt("retry_after_ms"),
                        rs.getInt("latency_ms")),
                ChaosStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "deliver_by"),
                instant(rs, "delivered_at"),
                instant(rs, "ended_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
