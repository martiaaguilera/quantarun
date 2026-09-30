package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEvent;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class JobEventRepository {

    /** A single job's timeline is small; the cap only protects against pathological retry loops. */
    public static final int MAX_EVENTS_PER_JOB_READ = 1_000;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JobEventRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must be called inside the transaction that made the change it describes, so the two commit together. */
    public void append(UUID jobId, @Nullable UUID attemptId, JobEventType type, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO job_events (job_id, attempt_id, type, details)
                        VALUES (:jobId, :attemptId, :type, CAST(:details AS jsonb))
                        """)
                .param("jobId", jobId)
                .param("attemptId", attemptId)
                .param("type", type.name())
                .param("details", json.writeValueAsString(details))
                .update();
    }

    public List<JobEvent> findByJob(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, job_id, attempt_id, type, occurred_at, details FROM job_events
                        WHERE job_id = :jobId ORDER BY id LIMIT :limit
                        """)
                .param("jobId", jobId)
                .param("limit", MAX_EVENTS_PER_JOB_READ)
                .query((rs, row) -> new JobEvent(
                        rs.getLong("id"),
                        rs.getObject("job_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        JobEventType.valueOf(rs.getString("type")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        (ObjectNode) json.readTree(rs.getString("details"))))
                .list();
    }
}
