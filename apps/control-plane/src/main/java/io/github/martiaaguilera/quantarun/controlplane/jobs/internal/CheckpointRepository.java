package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CheckpointRepository {

    /** A staged workload has at most 100 stages (the stage_index CHECK), so a job's checkpoints are a small list. */
    public static final int MAX_STAGES = 100;

    public record Checkpoint(int stageIndex, UUID attemptId, JsonNode result, Instant committedAt) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    CheckpointRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Checkpoint> findLast(UUID jobId) {
        return jdbc.sql("""
                        SELECT stage_index, attempt_id, result, committed_at FROM job_checkpoints
                        WHERE job_id = :jobId ORDER BY stage_index DESC LIMIT 1
                        """).param("jobId", jobId).query(this::map).optional();
    }

    public Optional<Checkpoint> find(UUID jobId, int stageIndex) {
        return jdbc.sql("""
                        SELECT stage_index, attempt_id, result, committed_at FROM job_checkpoints
                        WHERE job_id = :jobId AND stage_index = :stage
                        """)
                .param("jobId", jobId)
                .param("stage", stageIndex)
                .query(this::map)
                .optional();
    }

    public List<Checkpoint> findAll(UUID jobId) {
        return jdbc.sql("""
                        SELECT stage_index, attempt_id, result, committed_at FROM job_checkpoints
                        WHERE job_id = :jobId ORDER BY stage_index LIMIT :limit
                        """)
                .param("jobId", jobId)
                .param("limit", MAX_STAGES)
                .query(this::map)
                .list();
    }

    /** The caller holds the attempt's row lock and has checked the stage order; the primary key is the last guard. */
    public void insert(UUID jobId, int stageIndex, UUID attemptId, String result) {
        jdbc.sql("""
                        INSERT INTO job_checkpoints (job_id, stage_index, attempt_id, result)
                        VALUES (:jobId, :stage, :attemptId, CAST(:result AS jsonb))
                        """)
                .param("jobId", jobId)
                .param("stage", stageIndex)
                .param("attemptId", attemptId)
                .param("result", result)
                .update();
    }

    private Checkpoint map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Checkpoint(
                rs.getInt("stage_index"),
                rs.getObject("attempt_id", UUID.class),
                json.readTree(rs.getString("result")),
                rs.getTimestamp("committed_at").toInstant());
    }
}
