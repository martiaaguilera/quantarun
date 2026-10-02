package io.github.martiaaguilera.quantarun.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire contract between workers and the control plane (version 1, under {@code /worker-api/v1}).
 *
 * <p>The prefix is deliberately outside the client API's {@code /api}: the servlet container maps each security
 * filter by URL pattern on the normalised path, so the two credential types can never be confused by path tricks.
 *
 * <p>Changing a record here changes both processes at once, which is the point: the contract has one definition.
 * Fields are only ever added, never repurposed, so a mixed-version fleet keeps working during a rolling upgrade.
 */
public final class WorkerProtocol {

    public static final String BASE_PATH = "/worker-api/v1";
    public static final String LABEL_PATTERN = "^[a-z0-9][a-z0-9.-]{0,62}$";

    private WorkerProtocol() {}

    /** Capacity a worker offers. Accelerators are simulated slots, so any laptop can model a GPU fleet. */
    public record Capacity(
            @NotNull @Min(1) @Max(1_024_000) Integer cpuMillis,
            @NotNull @Min(1) @Max(16_777_216) Integer memoryMib,
            @NotNull @Min(0) @Max(64) Integer accelerators,
            @NotNull @Min(1) @Max(256) Integer slots) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = LABEL_PATTERN)
            String name,

            @NotBlank @Size(max = 64) String version,
            @NotNull @Valid Capacity capacity,
            @NotNull @Size(max = 32) List<@NotNull @Pattern(regexp = LABEL_PATTERN) String> labels) {}

    /**
     * @param workerSecret per-registration credential for every later call; shown once, stored hashed.
     * @param heartbeatIntervalMillis how often to heartbeat; the control plane owns the timing so it can tune
     *     detection thresholds without redeploying workers.
     */
    public record RegisterResponse(
            UUID workerId, String workerSecret, long heartbeatIntervalMillis, long leaseDurationMillis) {}

    /** @param activeAttemptIds attempts the worker is currently executing; their leases are renewed. */
    public record HeartbeatRequest(@NotNull @Size(max = 256) List<@NotNull UUID> activeAttemptIds) {}

    /**
     * @param lifecycle the worker's lifecycle as the control plane sees it; {@code DRAINING} means finish current
     *     work but claim nothing new.
     * @param cancelAttemptIds attempts whose jobs were cancelled; stop them and report CANCELLED.
     * @param lostAttemptIds attempts this worker no longer owns (lease expired and recovered); stop them and never
     *     report a result for them.
     */
    public record HeartbeatResponse(
            WorkerLifecycleView lifecycle, List<UUID> cancelAttemptIds, List<UUID> lostAttemptIds) {}

    public enum WorkerLifecycleView {
        ACTIVE,
        DRAINING
    }

    /** @param maxAssignments how many assignments the worker can start now (its free execution slots). */
    public record ClaimRequest(@NotNull @Min(1) @Max(256) Integer maxAssignments) {}

    /**
     * Work the scheduler placed on this worker. Claiming starts the attempt: from here on the worker owns it until it
     * reports an outcome or its lease expires.
     *
     * @param attemptId the fencing token for every later call about this execution.
     */
    public record Assignment(
            UUID attemptId,
            UUID jobId,
            int attemptNo,
            String workloadType,
            Map<String, Object> payload,
            int timeoutSeconds) {}

    public record ClaimResponse(List<Assignment> assignments) {}

    public enum AttemptOutcome {
        SUCCEEDED,
        FAILED,
        /** The worker stopped because the job was cancelled (it saw the attempt in {@code cancelAttemptIds}). */
        CANCELLED
    }

    /**
     * Why an attempt failed. The class, not the message, drives the retry decision (docs/FAILURE_SEMANTICS.md).
     * {@code WORKER_LOST} is never reported by a worker: the control plane assigns it when a lease expires.
     */
    public enum FailureClass {
        TRANSIENT,
        TIMEOUT,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        WORKER_LOST,
        RESOURCE_EXHAUSTED,
        INVALID_INPUT,
        NON_RETRYABLE,
        INTERNAL
    }

    /**
     * @param failureClass required when {@code outcome} is FAILED, absent otherwise.
     * @param result workload output on success; size-capped by the control plane.
     */
    public record ReportRequest(
            @NotNull AttemptOutcome outcome,
            FailureClass failureClass,
            @Size(max = 1000) String message,
            Map<String, Object> result) {}

    /** @param jobStatus the job's status after this report was applied (or after the identical earlier report). */
    public record ReportResponse(UUID attemptId, String attemptStatus, String jobStatus) {}
}
