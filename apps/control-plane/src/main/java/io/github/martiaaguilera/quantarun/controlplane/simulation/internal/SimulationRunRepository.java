package io.github.martiaaguilera.quantarun.controlplane.simulation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SimulationRunRepository {

    public record StoredRun(
            UUID id,
            String scenario,
            long seed,
            int jobCount,
            @Nullable UUID requestedByProject,
            String results,
            Instant createdAt) {}

    private static final String COLUMNS = "id, scenario, seed, job_count, requested_by_project, results, created_at";

    private final JdbcClient jdbc;

    SimulationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StoredRun insert(String scenario, long seed, int jobCount, @Nullable UUID project, String results) {
        return jdbc.sql("""
                        INSERT INTO simulation_runs (scenario, seed, job_count, requested_by_project, results)
                        VALUES (:scenario, :seed, :jobCount, :project, CAST(:results AS jsonb))
                        RETURNING
                        """ + COLUMNS)
                .param("scenario", scenario)
                .param("seed", seed)
                .param("jobCount", jobCount)
                .param("project", project)
                .param("results", results)
                .query(this::map)
                .single();
    }

    public Optional<StoredRun> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM simulation_runs WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** {@code project} null lists every run (operators); otherwise only that project's. */
    public List<StoredRun> findRecent(@Nullable UUID project, int limit) {
        var sql = "SELECT " + COLUMNS + " FROM simulation_runs"
                + (project == null ? "" : " WHERE requested_by_project = :project")
                + " ORDER BY id DESC LIMIT :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        if (project != null) {
            statement = statement.param("project", project);
        }
        return statement.query(this::map).list();
    }

    private StoredRun map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredRun(
                rs.getObject("id", UUID.class),
                rs.getString("scenario"),
                rs.getLong("seed"),
                rs.getInt("job_count"),
                rs.getObject("requested_by_project", UUID.class),
                rs.getString("results"),
                rs.getTimestamp("created_at").toInstant());
    }
}
