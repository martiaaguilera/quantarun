package io.github.martiaaguilera.quantarun.controlplane.scheduler.internal;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DecisionRepository {

    public record StoredDecision(
            long id,
            UUID jobId,
            @Nullable UUID attemptId,
            String policy,
            String outcome,
            @Nullable UUID chosenWorkerId,
            String reason,
            JsonNode candidates,
            long queueWaitMs,
            Instant decidedAt) {}

    private static final String COLUMNS =
            "id, job_id, attempt_id, policy, outcome, chosen_worker_id, reason, candidates, queue_wait_ms, decided_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    DecisionRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(
            PlacementDecision decision, SchedulingPolicy policy, @Nullable UUID attemptId, long queueWaitMs) {
        jdbc.sql("""
                        INSERT INTO scheduler_decisions (job_id, attempt_id, policy, outcome, chosen_worker_id, reason,
                                                         candidates, queue_wait_ms)
                        VALUES (:jobId, :attemptId, :policy, :outcome, :workerId, :reason, CAST(:candidates AS jsonb),
                                :queueWaitMs)
                        """)
                .param("jobId", decision.job().id())
                .param("attemptId", attemptId)
                .param("policy", policy.name())
                .param("outcome", decision.outcome().name())
                .param("workerId", decision.chosenWorkerId())
                .param("reason", decision.reason())
                .param("candidates", json.writeValueAsString(decision.candidates()))
                .param("queueWaitMs", queueWaitMs)
                .update();
    }

    public List<StoredDecision> findByJob(UUID jobId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM scheduler_decisions WHERE job_id = :jobId ORDER BY id LIMIT :limit")
                .param("jobId", jobId)
                .param("limit", limit)
                .query((rs, row) -> map(rs))
                .list();
    }

    public List<StoredDecision> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scheduler_decisions ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, row) -> map(rs))
                .list();
    }

    private StoredDecision map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredDecision(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("policy"),
                rs.getString("outcome"),
                rs.getObject("chosen_worker_id", UUID.class),
                rs.getString("reason"),
                json.readTree(rs.getString("candidates")),
                rs.getLong("queue_wait_ms"),
                rs.getTimestamp("decided_at").toInstant());
    }
}
