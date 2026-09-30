package io.github.martiaaguilera.quantarun.controlplane.projects.internal;

import io.github.martiaaguilera.quantarun.controlplane.projects.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final String COLUMNS = "id, name, weight, created_at";

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

    public List<Project> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects ORDER BY name")
                .query(Project.class)
                .list();
    }
}
