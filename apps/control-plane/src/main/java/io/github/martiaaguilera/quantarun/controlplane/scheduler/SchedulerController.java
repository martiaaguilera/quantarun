package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobQueries;
import io.github.martiaaguilera.quantarun.controlplane.projects.Project;
import io.github.martiaaguilera.quantarun.controlplane.projects.Projects;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.DecisionRepository;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.DecisionRepository.StoredDecision;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.FairShareRepository;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SchedulerController {

    static final int MAX_DECISIONS = 200;

    record SchedulerStatus(
            boolean enabled,
            SchedulingPolicy policy,
            List<SchedulingPolicy> availablePolicies,
            int windowSize,
            Duration idleDelay,
            int loops) {}

    /**
     * @param virtualTime service received per unit of weight; under FAIR_SHARE the lowest backlogged project goes next.
     */
    record ProjectFairness(
            UUID projectId,
            String name,
            int weight,
            double virtualTime,
            @Nullable Integer maxQueuedJobs,
            @Nullable Integer maxRunningJobs,
            @Nullable Integer maxAccelerators) {}

    /** @param systemVirtualTime the floor a project returning from idle is raised to. */
    record FairnessView(double systemVirtualTime, List<ProjectFairness> projects) {}

    private final SchedulerProperties properties;
    private final DecisionRepository decisions;
    private final FairShareRepository fairShare;
    private final Projects projects;
    private final JobQueries jobs;

    SchedulerController(
            SchedulerProperties properties,
            DecisionRepository decisions,
            FairShareRepository fairShare,
            Projects projects,
            JobQueries jobs) {
        this.properties = properties;
        this.decisions = decisions;
        this.fairShare = fairShare;
        this.projects = projects;
        this.jobs = jobs;
    }

    @GetMapping("/api/v1/scheduler")
    SchedulerStatus status(Caller caller) {
        return new SchedulerStatus(
                properties.enabled(),
                properties.policy(),
                SchedulingCycle.availablePolicies(),
                properties.windowSize(),
                properties.idleDelay(),
                properties.loops());
    }

    /** Cross-project view of placement decisions: operators only. */
    @GetMapping("/api/v1/scheduler/decisions")
    List<StoredDecision> recentDecisions(
            Caller caller, @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_DECISIONS) int limit) {
        caller.requireAdmin();
        return decisions.findRecent(limit);
    }

    /** Each project's fair-share standing, least served first. Cross-project, so operators only. */
    @GetMapping("/api/v1/scheduler/fairness")
    FairnessView fairness(Caller caller) {
        caller.requireAdmin();
        var clocks = fairShare.all();
        var byId = projects
                .findAll(clocks.stream()
                        .map(FairShareRepository.ProjectClock::projectId)
                        .toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Project::id, project -> project));
        var rows = clocks.stream()
                .filter(clock -> byId.containsKey(clock.projectId()))
                .map(clock -> {
                    var project = byId.get(clock.projectId());
                    return new ProjectFairness(
                            project.id(),
                            project.name(),
                            project.weight(),
                            clock.virtualTime(),
                            project.maxQueuedJobs(),
                            project.maxRunningJobs(),
                            project.maxAccelerators());
                })
                .toList();
        return new FairnessView(fairShare.systemVirtualTime(), rows);
    }

    /** Why this job ran where it ran, or why it is still waiting. Scoped like the job itself. */
    @GetMapping("/api/v1/jobs/{jobId}/decisions")
    List<StoredDecision> jobDecisions(Caller caller, @PathVariable UUID jobId) {
        jobs.get(caller, jobId);
        return decisions.findByJob(jobId, MAX_DECISIONS);
    }
}
