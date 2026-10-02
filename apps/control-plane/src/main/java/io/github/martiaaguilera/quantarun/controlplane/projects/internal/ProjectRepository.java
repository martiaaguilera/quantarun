package io.github.martiaaguilera.quantarun.controlplane.projects.internal;

import io.github.martiaaguilera.quantarun.controlplane.projects.Project;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final String COLUMNS =
            "id, name, weight, max_queued_jobs, max_running_jobs, max_accelerators, created_at";

    private final JdbcClient jdbc;

    ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the name is taken. */
    public Project insert(String name, int weight) {
        return jdbc.sql("INSERT INTO projects (name, weight) VALUES (:name, :weight) RETURNING " + COLUMNS)
                .param("name", name)
                .param("weight", weight)
                .query(Project.class)
                .single();
    }

    public Optional<Project> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects WHERE id = :id")
                .param("id", id)
                .query(Project.class)
                .optional();
    }

    public List<Project> findAll(Collection<UUID> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .query(Project.class)
                .list();
    }

    public Optional<Project> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Project.class)
                .optional();
    }

    /** Replaces the weight and all three quotas at once; a null quota means unlimited. */
    public Optional<Project> updateLimits(
            UUID id,
            int weight,
            @Nullable Integer maxQueuedJobs,
            @Nullable Integer maxRunningJobs,
            @Nullable Integer maxAccelerators) {
        return jdbc.sql("""
                        UPDATE projects
                        SET weight = :weight, max_queued_jobs = :maxQueued, max_running_jobs = :maxRunning,
                            max_accelerators = :maxAccelerators
                        WHERE id = :id
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("weight", weight)
                .param("maxQueued", maxQueuedJobs)
                .param("maxRunning", maxRunningJobs)
                .param("maxAccelerators", maxAccelerators)
                .query(Project.class)
                .optional();
    }

    public List<Project> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects ORDER BY name")
                .query(Project.class)
                .list();
    }
}
