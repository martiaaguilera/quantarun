package io.github.martiaaguilera.quantarun.controlplane.chaos;

import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.EnumMap;
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

/**
 * Operator-only: a fault reaches the whole shared fleet, whoever's jobs run there, so no project key may start one.
 * Experiments can only be created where {@code quantarun.chaos.enabled} is on.
 */
@RestController
@RequestMapping("/api/v1/chaos")
class ChaosController {

    static final int MAX_LISTED = 100;

    record Catalog(boolean enabled, List<FaultCatalog.FaultDefinition> faults) {}

    /** Aim at a worker directly, or at "whichever worker runs this job". Unset parameters take the fault's defaults. */
    record CreateRequest(
            @NotNull ChaosFault fault,
            @Nullable UUID workerId,
            @Nullable UUID jobId,
            @Nullable Integer delayMs,
            @Nullable Integer durationMs,
            @Nullable Integer count,
            @Nullable Integer retryAfterMs,
            @Nullable Integer latencyMs) {}

    record ExperimentResponse(
            UUID id,
            ChaosFault fault,
            UUID workerId,
            @Nullable UUID jobId,
            FaultParameters parameters,
            ChaosStatus status,
            Instant createdAt,
            Instant deliverBy,
            @Nullable Instant deliveredAt,
            @Nullable Instant endedAt,
            @Nullable ChaosTimeline timeline) {

        static ExperimentResponse of(ChaosExperiment experiment, @Nullable ChaosTimeline timeline) {
            return new ExperimentResponse(
                    experiment.id(),
                    experiment.fault(),
                    experiment.workerId(),
                    experiment.jobId(),
                    experiment.parameters(),
                    experiment.status(),
                    experiment.createdAt(),
                    experiment.deliverBy(),
                    experiment.deliveredAt(),
                    experiment.endedAt(),
                    timeline);
        }
    }

    private final ChaosExperiments experiments;
    private final ChaosProperties properties;

    ChaosController(ChaosExperiments experiments, ChaosProperties properties) {
        this.experiments = experiments;
        this.properties = properties;
    }

    @GetMapping("/faults")
    Catalog faults(Caller caller) {
        admin(caller);
        return new Catalog(properties.enabled(), FaultCatalog.definitions());
    }

    @PostMapping("/experiments")
    @ResponseStatus(HttpStatus.CREATED)
    ExperimentResponse create(Caller caller, @Valid @RequestBody CreateRequest request) {
        var admin = admin(caller);
        var requested = new EnumMap<FaultCatalog.Parameter, @Nullable Integer>(FaultCatalog.Parameter.class);
        requested.put(FaultCatalog.Parameter.DELAY_MS, request.delayMs());
        requested.put(FaultCatalog.Parameter.DURATION_MS, request.durationMs());
        requested.put(FaultCatalog.Parameter.COUNT, request.count());
        requested.put(FaultCatalog.Parameter.RETRY_AFTER_MS, request.retryAfterMs());
        requested.put(FaultCatalog.Parameter.LATENCY_MS, request.latencyMs());
        var parameters = FaultCatalog.resolve(request.fault(), requested);
        var created = experiments.create(
                admin, request.fault(), new ChaosExperiments.Target(request.workerId(), request.jobId()), parameters);
        return ExperimentResponse.of(created, null);
    }

    @GetMapping("/experiments")
    List<ExperimentResponse> list(
            Caller caller, @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LISTED) int limit) {
        return experiments.recent(admin(caller), limit).stream()
                .map(experiment -> ExperimentResponse.of(experiment, null))
                .toList();
    }

    /** The experiment with its recovery timeline, built from the records as they are now. */
    @GetMapping("/experiments/{experimentId}")
    ExperimentResponse get(Caller caller, @PathVariable UUID experimentId) {
        var admin = admin(caller);
        var experiment = experiments.get(admin, experimentId);
        return ExperimentResponse.of(experiment, experiments.timeline(admin, experiment));
    }

    @PostMapping("/experiments/{experimentId}/cancel")
    ExperimentResponse cancel(Caller caller, @PathVariable UUID experimentId) {
        return ExperimentResponse.of(experiments.cancel(admin(caller), experimentId), null);
    }

    private static Caller.Admin admin(Caller caller) {
        caller.requireAdmin();
        return (Caller.Admin) caller;
    }
}
