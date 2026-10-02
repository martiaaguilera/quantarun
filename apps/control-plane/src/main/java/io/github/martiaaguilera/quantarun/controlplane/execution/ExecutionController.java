package io.github.martiaaguilera.quantarun.controlplane.execution;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerAuthenticationFilter;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerPrincipal;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    ExecutionController(WorkerRegistry registry, JobAttempts attempts) {
        this.registry = registry;
        this.attempts = attempts;
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
        return new WorkerProtocol.HeartbeatResponse(lifecycle, renewal.cancelRequested(), renewal.lost());
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
                        claimed.timeoutSeconds()))
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
        return switch (attempts.report(
                workerId, attemptId, request.outcome(), request.failureClass(), request.message(), request.result())) {
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
    }

    private static ApiException invalidReport(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPORT", detail);
    }
}
