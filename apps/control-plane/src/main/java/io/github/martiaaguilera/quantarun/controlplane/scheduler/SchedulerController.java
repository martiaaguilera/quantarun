package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobQueries;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.DecisionRepository;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.internal.DecisionRepository.StoredDecision;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

    private final SchedulerProperties properties;
    private final DecisionRepository decisions;
    private final JobQueries jobs;

    SchedulerController(SchedulerProperties properties, DecisionRepository decisions, JobQueries jobs) {
        this.properties = properties;
        this.decisions = decisions;
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

    /** Why this job ran where it ran, or why it is still waiting. Scoped like the job itself. */
    @GetMapping("/api/v1/jobs/{jobId}/decisions")
    List<StoredDecision> jobDecisions(Caller caller, @PathVariable UUID jobId) {
        jobs.get(caller, jobId);
        return decisions.findByJob(jobId, MAX_DECISIONS);
    }
}
