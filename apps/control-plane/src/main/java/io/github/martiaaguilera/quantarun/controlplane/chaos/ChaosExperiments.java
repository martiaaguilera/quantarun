package io.github.martiaaguilera.quantarun.controlplane.chaos;

import io.github.martiaaguilera.quantarun.controlplane.chaos.internal.ChaosExperimentRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobQueries;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkerAttempt;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.Worker;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerNotFoundException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chaos experiments: an operator aims one predefined fault at one live worker, the worker receives it in its next
 * heartbeat response, and the timeline shows how the system detected and recovered from it.
 *
 * <p>Nothing here reaches into a worker: the control plane never opens a connection to one (ADR-0005). A worker that
 * was not started with chaos enabled ignores what it receives.
 */
@Component
public class ChaosExperiments {

    private static final Logger log = LoggerFactory.getLogger(ChaosExperiments.class);
    static final int MAX_AFFECTED_JOBS = 50;
    static final int MAX_EVENTS_PER_JOB = 50;
    static final int MAX_REGISTRATIONS = 10;
    /** Covers a lease (15 s), the offline threshold (15 s) and a retry's backoff after a time-boxed fault ends. */
    static final Duration SETTLE_TIME = Duration.ofMinutes(2);
    /** Counted faults hit the next attempts or provider calls, whenever they come; this bounds how far we look. */
    static final Duration COUNTED_FAULT_WINDOW = Duration.ofMinutes(10);

    /** Exactly one of the two is set. */
    public record Target(@Nullable UUID workerId, @Nullable UUID jobId) {}

    private final ChaosExperimentRepository experiments;
    private final ChaosProperties properties;
    private final WorkerRegistry workers;
    private final JobQueries jobs;
    private final MeterRegistry meters;

    ChaosExperiments(
            ChaosExperimentRepository experiments,
            ChaosProperties properties,
            WorkerRegistry workers,
            JobQueries jobs,
            MeterRegistry meters) {
        this.experiments = experiments;
        this.properties = properties;
        this.workers = workers;
        this.jobs = jobs;
        this.meters = meters;
    }

    @Transactional
    public ChaosExperiment create(Caller.Admin caller, ChaosFault fault, Target target, FaultParameters parameters) {
        requireEnabled();
        var workerId = resolveWorker(caller, target);
        var worker = workers.find(workerId).orElseThrow(() -> new WorkerNotFoundException(workerId));
        if (!worker.lifecycle().isLive()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "WORKER_NOT_ACTIVE",
                    "Worker " + workerId + " is " + worker.lifecycle() + "; a fault can only reach a live worker.");
        }
        experiments.expireOverdue();
        var created = experiments
                .insertIfBelowLimit(
                        fault,
                        workerId,
                        target.jobId(),
                        parameters,
                        properties.deliveryWindow(),
                        properties.maxPendingPerWorker())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "TOO_MANY_PENDING_FAULTS",
                        "Worker " + workerId + " already has " + properties.maxPendingPerWorker()
                                + " undelivered faults."));
        log.atWarn()
                .addKeyValue("experimentId", created.id())
                .addKeyValue("fault", fault)
                .addKeyValue("workerId", workerId)
                .addKeyValue("jobId", target.jobId())
                .log("Chaos experiment created");
        return created;
    }

    /**
     * Called on every heartbeat. When chaos is disabled no experiment can be created, and any left over from an
     * earlier, enabled run is not delivered either.
     */
    public List<WorkerProtocol.ChaosDirective> deliver(UUID workerId) {
        if (!properties.enabled()) {
            return List.of();
        }
        var delivered = experiments.deliver(workerId);
        for (var experiment : delivered) {
            log.atWarn()
                    .addKeyValue("experimentId", experiment.id())
                    .addKeyValue("fault", experiment.fault())
                    .addKeyValue("workerId", workerId)
                    .log("Chaos fault delivered");
            Counter.builder("quantarun.chaos.faults.delivered")
                    .description("Chaos faults handed to a worker")
                    .tag("fault", experiment.fault().name())
                    .register(meters)
                    .increment();
        }
        return delivered.stream().map(ChaosExperiment::toDirective).toList();
    }

    public ChaosExperiment cancel(Caller.Admin caller, UUID id) {
        requireEnabled();
        var current = get(caller, id);
        return experiments
                .cancel(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "EXPERIMENT_NOT_PENDING",
                        "Experiment " + id + " is " + current.status()
                                + "; only a pending experiment can be cancelled."));
    }

    public ChaosExperiment get(Caller.Admin caller, UUID id) {
        experiments.expireOverdue();
        return experiments
                .findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND", "Chaos experiment " + id + " not found."));
    }

    public List<ChaosExperiment> recent(Caller.Admin caller, int limit) {
        experiments.expireOverdue();
        return experiments.findRecent(limit);
    }

    public ChaosTimeline timeline(Caller.Admin caller, ChaosExperiment experiment) {
        var target = workers.find(experiment.workerId()).orElse(null);
        var delivered = experiment.deliveredAt();
        if (delivered == null) {
            return ChaosTimeline.build(experiment, target, List.of(), List.of());
        }
        var windowEnd = ChaosTimeline.firesAt(experiment, delivered).plus(effectWindow(experiment));
        var jobIds = new LinkedHashSet<UUID>();
        if (experiment.jobId() != null) {
            jobIds.add(experiment.jobId());
        }
        jobs.attemptsOnWorker(caller, experiment.workerId(), delivered, windowEnd, MAX_AFFECTED_JOBS).stream()
                .map(WorkerAttempt::jobId)
                .forEach(jobIds::add);
        var affected = jobIds.stream()
                .limit(MAX_AFFECTED_JOBS)
                .map(jobId -> new ChaosTimeline.AffectedJob(
                        jobId,
                        jobs.get(caller, jobId).status().name(),
                        jobs.events(caller, jobId).stream()
                                .limit(MAX_EVENTS_PER_JOB)
                                .toList()))
                .toList();
        var registrations = target == null
                ? List.<Worker>of()
                : workers.registrationsSince(target.name(), delivered, MAX_REGISTRATIONS).stream()
                        .filter(worker -> !worker.id().equals(target.id()))
                        .toList();
        return ChaosTimeline.build(experiment, target, affected, registrations);
    }

    static Duration effectWindow(ChaosExperiment experiment) {
        return switch (experiment.fault()) {
            case KILL_WORKER -> SETTLE_TIME;
            case PAUSE_HEARTBEAT, STOP_CLAIMING, NETWORK_LATENCY ->
                Duration.ofMillis(experiment.parameters().durationMs()).plus(SETTLE_TIME);
            case STALL_ATTEMPTS, PROVIDER_RATE_LIMITED, PROVIDER_ERROR, PROVIDER_MALFORMED -> COUNTED_FAULT_WINDOW;
        };
    }

    private UUID resolveWorker(Caller.Admin caller, Target target) {
        if ((target.workerId() == null) == (target.jobId() == null)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_CHAOS_TARGET", "Give exactly one of workerId and jobId.");
        }
        if (target.workerId() != null) {
            return target.workerId();
        }
        return jobs.activeWorker(caller, target.jobId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "JOB_NOT_ON_A_WORKER",
                        "Job " + target.jobId() + " has no assigned or running attempt, so no worker holds it."));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CHAOS_DISABLED",
                    "Chaos experiments are disabled in this deployment (quantarun.chaos.enabled).");
        }
    }
}
