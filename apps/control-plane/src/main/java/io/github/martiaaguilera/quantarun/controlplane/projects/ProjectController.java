package io.github.martiaaguilera.quantarun.controlplane.projects;

import io.github.martiaaguilera.quantarun.controlplane.projects.internal.ProjectRepository;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {

    record CreateProjectRequest(
            @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "lowercase letters, digits and '-'")
            String name,

            @Min(1) @Max(1000) Integer weight) {}

    /**
     * The full set of limits, replaced as a whole (PUT semantics): an omitted quota becomes unlimited, so a request
     * always states the complete policy rather than patching part of it.
     */
    record ProjectLimits(
            @NotNull @Min(1) @Max(1000) Integer weight,
            @Min(1) @Max(1_000_000) @Nullable Integer maxQueuedJobs,
            @Min(1) @Max(100_000) @Nullable Integer maxRunningJobs,
            @Min(0) @Max(10_000) @Nullable Integer maxAccelerators) {}

    private final ProjectRepository projects;

    ProjectController(ProjectRepository projects) {
        this.projects = projects;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Project create(Caller caller, @Valid @RequestBody CreateProjectRequest request) {
        caller.requireAdmin();
        try {
            return projects.insert(request.name(), request.weight() == null ? 1 : request.weight());
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "PROJECT_NAME_TAKEN", "A project named " + request.name() + " exists.");
        }
    }

    @GetMapping
    List<Project> list(Caller caller) {
        caller.requireAdmin();
        return projects.findAll();
    }

    @GetMapping("/{projectId}")
    Project get(Caller caller, @PathVariable UUID projectId) {
        // A key for another project gets the same 404 as a missing project: existence is not disclosed.
        if (!caller.canAccessProject(projectId)) {
            throw projectNotFound(projectId);
        }
        return projects.findById(projectId).orElseThrow(() -> projectNotFound(projectId));
    }

    /** Operators set a project's fair-share weight and quotas. They apply from the next submission or cycle on. */
    @PutMapping("/{projectId}/limits")
    Project updateLimits(Caller caller, @PathVariable UUID projectId, @Valid @RequestBody ProjectLimits limits) {
        caller.requireAdmin();
        return projects.updateLimits(
                        projectId,
                        limits.weight(),
                        limits.maxQueuedJobs(),
                        limits.maxRunningJobs(),
                        limits.maxAccelerators())
                .orElseThrow(() -> projectNotFound(projectId));
    }

    private static ApiException projectNotFound(UUID projectId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project " + projectId + " not found.");
    }
}
