package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.simulation.internal.SimulationRunRepository;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a scenario against several policies and keeps the result. Runs are synchronous: a bounded trace (at most
 * {@value Scenario#MAX_JOBS} jobs, six policies) simulates in seconds, and no transaction is open while it does.
 */
@RestController
@RequestMapping("/api/v1/simulations")
class SimulationController {

    static final int MAX_LISTED = 100;

    /** @param policies defaults to every policy. */
    record RunRequest(
            @NotNull Scenario scenario,
            @NotNull Long seed,
            @Min(1) @Max(Scenario.MAX_JOBS) @Nullable Integer jobCount,
            @Size(min = 1, max = 6) @Nullable List<@NotNull SchedulingPolicy> policies) {}

    record ScenarioInfo(Scenario scenario, int defaultJobCount) {}

    record RunResponse(UUID id, String scenario, long seed, int jobCount, Instant createdAt, JsonNode results) {}

    private final SimulationRunRepository runs;
    private final JsonMapper json;
    private final SimulationAdmission admission;

    SimulationController(SimulationRunRepository runs, JsonMapper json, SimulationAdmission admission) {
        this.runs = runs;
        this.json = json;
        this.admission = admission;
    }

    @GetMapping("/scenarios")
    List<ScenarioInfo> scenarios(Caller caller) {
        return Arrays.stream(Scenario.values())
                .map(scenario -> new ScenarioInfo(scenario, Scenario.DEFAULT_JOBS))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RunResponse run(Caller caller, @Valid @RequestBody RunRequest request) {
        var policies = request.policies() == null ? List.of(SchedulingPolicy.values()) : request.policies();
        var jobCount = request.jobCount() == null ? Scenario.DEFAULT_JOBS : request.jobCount();
        if (!admission.tryEnter()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "SIMULATION_BUSY",
                    "The maximum number of simulations is already running. Retry when one finishes.");
        }
        SimulationResult result;
        try {
            // Simulated outside any transaction: only the finished result is written, in one short statement.
            result = Simulations.run(request.scenario(), request.seed(), jobCount, policies);
        } finally {
            admission.leave();
        }
        var stored = runs.insert(
                request.scenario().name(),
                request.seed(),
                jobCount,
                projectOf(caller),
                json.writeValueAsString(result));
        return toResponse(stored);
    }

    @GetMapping("/{runId}")
    RunResponse get(Caller caller, @PathVariable UUID runId) {
        return runs.findById(runId)
                .filter(run -> visibleTo(caller, run))
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SIMULATION_NOT_FOUND", "Simulation run " + runId + " not found."));
    }

    @GetMapping
    List<RunResponse> list(Caller caller, @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LISTED) int limit) {
        return runs.findRecent(projectOf(caller), limit).stream()
                .map(this::toResponse)
                .toList();
    }

    private static @Nullable UUID projectOf(Caller caller) {
        return switch (caller) {
            case Caller.Admin _ -> null;
            case Caller.ProjectMember member -> member.projectId();
        };
    }

    /** Operators see every run; a project sees the runs its own keys requested. */
    private static boolean visibleTo(Caller caller, SimulationRunRepository.StoredRun run) {
        return switch (caller) {
            case Caller.Admin _ -> true;
            case Caller.ProjectMember member -> member.projectId().equals(run.requestedByProject());
        };
    }

    private RunResponse toResponse(SimulationRunRepository.StoredRun run) {
        return new RunResponse(
                run.id(), run.scenario(), run.seed(), run.jobCount(), run.createdAt(), json.readTree(run.results()));
    }
}
