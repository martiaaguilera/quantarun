package io.github.martiaaguilera.quantarun.controlplane.execution;

import io.github.martiaaguilera.quantarun.controlplane.chaos.ChaosExperiments;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerAuthenticationFilter;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerPrincipal;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The execution half of the worker protocol: heartbeat with lease renewal, claim, and report. It lives in its own
 * module because it needs both {@code workers} (liveness) and {@code jobs} (attempts), and {@code jobs} already
 * depends on {@code workers}; putting it in either would close a cycle.
 *
 * <p>Every call first checks that the registration is still live, so a worker that was retired cannot claim, renew
 * or report under its old identity (invariant I11). Reports are additionally fenced by attempt id and worker id.
 */
@RestController
@RequestMapping(WorkerProtocol.BASE_PATH)
class ExecutionController {

    private final WorkerRegistry registry;
    private final JobAttempts attempts;
    private final ChaosExperiments chaos;
    private final JsonMapper json;

    ExecutionController(WorkerRegistry registry, JobAttempts attempts, ChaosExperiments chaos, JsonMapper json) {
        this.registry = registry;
        this.attempts = attempts;
        this.chaos = chaos;
        this.json = json;
    }

    /**
     * Liveness is recorded before leases are renewed: a slow renewal must never make a live worker look silent.
     * The two are separate transactions on purpose; the heartbeat row is never locked together with attempts.
     */
    @PostMapping("/heartbeat")
    WorkerProtocol.HeartbeatResponse heartbeat(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @Valid @RequestBody WorkerProtocol.HeartbeatRequest request) {
        var workerId = principal.requireRegisteredWorker();
        var lifecycle = registry.heartbeat(workerId);
        var renewal = attempts.renewLeases(
                workerId, request.activeAttemptIds().stream().distinct().toList());
        return new WorkerProtocol.HeartbeatResponse(
                lifecycle, renewal.cancelRequested(), renewal.lost(), chaos.deliver(workerId));
    }

    /**
     * A DRAINING worker may still claim: the scheduler never places new work on it, so anything ASSIGNED to it was
     * placed before the drain and is already its work. Refusing would leave that attempt unclaimed while heartbeats
     * keep renewing its lease, and the job would never run.
     */
    @PostMapping("/claim")
    WorkerProtocol.ClaimResponse claim(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @Valid @RequestBody WorkerProtocol.ClaimRequest request) {
        var workerId = principal.requireRegisteredWorker();
        registry.requireLive(workerId);
        var assignments = attempts.claim(workerId, request.maxAssignments()).stream()
                .map(claimed -> new WorkerProtocol.Assignment(
                        claimed.attemptId(),
                        claimed.jobId(),
                        claimed.attemptNo(),
                        claimed.workloadType(),
                        claimed.payload(),
                        claimed.timeoutSeconds(),
                        claimed.lastCheckpoint() == null
                                ? null
                                : new WorkerProtocol.Checkpoint(
                                        claimed.lastCheckpoint().stageIndex(),
                                        claimed.lastCheckpoint().result()),
                        claimed.traceParent()))
                .toList();
        return new WorkerProtocol.ClaimResponse(assignments);
    }

    @PostMapping("/attempts/{attemptId}/report")
    WorkerProtocol.ReportResponse report(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerProtocol.ReportRequest request) {
        var workerId = principal.requireRegisteredWorker();
        requireConsistentFailureClass(request);
        registry.requireLive(workerId);
        var retryAfter = request.retryAfterMillis() == null ? null : Duration.ofMillis(request.retryAfterMillis());
        return switch (attempts.report(
                workerId,
                attemptId,
                request.outcome(),
                request.failureClass(),
                request.message(),
                request.result(),
                retryAfter)) {
            case JobAttempts.ReportResult.Applied(var id, var attemptStatus, var jobStatus) ->
                new WorkerProtocol.ReportResponse(id, attemptStatus.name(), jobStatus.name());
            case JobAttempts.ReportResult.AlreadyRecorded(var id, var attemptStatus, var jobStatus) ->
                new WorkerProtocol.ReportResponse(id, attemptStatus.name(), jobStatus.name());
            case JobAttempts.ReportResult.Rejected(var id, var attemptStatus) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ATTEMPT_NOT_ACTIVE",
                        "Attempt " + id + " already ended as " + attemptStatus + "; this report was not applied.");
            case JobAttempts.ReportResult.NotFound(var id) ->
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ATTEMPT_NOT_FOUND",
                        "Attempt " + id + " is not assigned to this worker.");
            case JobAttempts.ReportResult.NotClaimed(var id) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ATTEMPT_NOT_CLAIMED",
                        "Attempt " + id + " was never claimed, so it has no outcome to report.");
            case JobAttempts.ReportResult.LeaseExpired(var id) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "LEASE_EXPIRED",
                        "The lease of attempt " + id + " expired before this report; the attempt is being recovered.");
        };
    }

    /**
     * Commits one stage of a staged workload. The 8 KiB cap is checked here so an oversized stage result is a clear
     * client error rather than a database constraint failure.
     */
    @PostMapping("/attempts/{attemptId}/checkpoints")
    WorkerProtocol.CheckpointResponse checkpoint(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerProtocol.CheckpointRequest request) {
        var workerId = principal.requireRegisteredWorker();
        registry.requireLive(workerId);
        if (json.writeValueAsBytes(request.result()).length > JobAttempts.MAX_CHECKPOINT_BYTES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CHECKPOINT_TOO_LARGE",
                    "A checkpoint result may be at most " + JobAttempts.MAX_CHECKPOINT_BYTES + " bytes.");
        }
        return switch (attempts.commitCheckpoint(workerId, attemptId, request.stageIndex(), request.result())) {
            case JobAttempts.CheckpointResult.Committed(var stage) ->
                new WorkerProtocol.CheckpointResponse(stage, false);
            case JobAttempts.CheckpointResult.AlreadyCommitted(var stage) ->
                new WorkerProtocol.CheckpointResponse(stage, true);
            case JobAttempts.CheckpointResult.OutOfOrder(var expected) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CHECKPOINT_OUT_OF_ORDER",
                        "Stage " + request.stageIndex() + " cannot be committed; the next stage is " + expected + ".");
            case JobAttempts.CheckpointResult.NotActive(var status) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ATTEMPT_NOT_ACTIVE",
                        "Attempt " + attemptId + " is " + status + "; only a running attempt can commit checkpoints.");
            case JobAttempts.CheckpointResult.LeaseExpired(var id) ->
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "LEASE_EXPIRED",
                        "The lease of attempt " + id + " expired; it can no longer commit checkpoints.");
            case JobAttempts.CheckpointResult.NotFound(var id) ->
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ATTEMPT_NOT_FOUND",
                        "Attempt " + id + " is not assigned to this worker.");
        };
    }

    /**
     * The failure class drives the retry decision, so a report without one (or with one on a success) is ambiguous.
     * WORKER_LOST is the control plane's verdict on an expired lease; a worker that is reporting is evidently not lost.
     */
    private static void requireConsistentFailureClass(WorkerProtocol.ReportRequest request) {
        var failed = request.outcome() == AttemptOutcome.FAILED;
        if (failed && request.failureClass() == null) {
            throw invalidReport("A FAILED report must carry a failureClass.");
        }
        if (!failed && request.failureClass() != null) {
            throw invalidReport("Only a FAILED report may carry a failureClass.");
        }
        if (request.failureClass() == FailureClass.WORKER_LOST) {
            throw invalidReport("WORKER_LOST is assigned by the control plane and cannot be reported.");
        }
        if (request.retryAfterMillis() != null && request.failureClass() != FailureClass.RATE_LIMITED) {
            throw invalidReport("retryAfterMillis is only meaningful for a RATE_LIMITED failure.");
        }
    }

    private static ApiException invalidReport(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", detail);
    }
}
