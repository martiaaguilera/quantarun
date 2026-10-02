package io.github.martiaaguilera.quantarun.controlplane.projects;

import io.github.martiaaguilera.quantarun.controlplane.projects.internal.ProjectRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** What other modules may know about projects: their weights and quotas. */
@Component
public class Projects {

    private final ProjectRepository projects;

    Projects(ProjectRepository projects) {
        this.projects = projects;
    }

    public List<Project> findAll(Collection<UUID> ids) {
        return projects.findAll(ids);
    }

    /**
     * Locks the project row for an admission decision and returns it. MANDATORY: the lock serialises concurrent
     * submissions of the project only if the count and the insert that follow run in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Project> lockForAdmission(UUID projectId) {
        return projects.lockById(projectId);
    }
}
