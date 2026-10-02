package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.AttemptRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.AttemptRepository.LockedAttempt;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerCapacity;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerProperties;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Execution side of the job lifecycle: claiming, lease renewal, and ending attempts. Every path that ends an attempt
 * (a worker's report or an expired lease) goes through {@link #endAttempt}, so the reservation is released and the
 * retry decision is taken in exactly one place.
 *
 * <p>Lock order everywhere is attempt row, then job row, then worker row. The scheduler locks job then worker, but
 * only for jobs without an active attempt, so the two never wait on each other in opposite orders.
 */
@Service
public class JobAttempts {

    private static final Logger log = LoggerFactory.getLogger(JobAttempts.class);
    public static final int MAX_RESULT_BYTES = 16 * 1024;

    public record Claimed(
            UUID attemptId,
            UUID jobId,
            int attemptNo,
            String workloadType,
            Map<String, Object> payload,
            int timeoutSeconds) {}

    public record Renewal(List<UUID> renewedRunning, List<UUID> cancelRequested, List<UUID> lost) {}

    /** Result of reporting an outcome. */
    public sealed interface ReportResult {
        record Applied(UUID attemptId, AttemptStatus attemptStatus, JobStatus jobStatus) implements ReportResult {}

        /** The same outcome was already recorded for this attempt: duplicate delivery, answered idempotently. */
        record AlreadyRecorded(UUID attemptId, AttemptStatus attemptStatus, JobStatus jobStatus)
                implements ReportResult {}

        /** The attempt ended differently (for example its lease expired and it was recovered): the report is fenced. */
        record Rejected(UUID attemptId, AttemptStatus attemptStatus) implements ReportResult {}

        record NotFound(UUID attemptId) implements ReportResult {}
    }

    private final AttemptRepository attempts;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final WorkerCapacity capacity;
    private final WorkerProperties workerProperties;
    private final RetryPolicy retryPolicy;
    private final JsonMapper json;
    private final RandomGenerator random = RandomGenerator.getDefault();

    JobAttempts(
            AttemptRepository attempts,
            JobRepository jobs,
            JobEventRepository events,
            WorkerCapacity capacity,
            WorkerProperties workerProperties,
            RetryPolicy retryPolicy,
            JsonMapper json) {
        this.attempts = attempts;
        this.jobs = jobs;
        this.events = events;
        this.capacity = capacity;
        this.workerProperties = workerProperties;
        this.retryPolicy = retryPolicy;
        this.json = json;
    }

    /** The claimed attempts and their jobs' RUNNING transitions commit together. */
    @Transactional
    public List<Claimed> claim(UUID workerId, int maxAssignments) {
        var claimed = attempts.claimAssigned(workerId, maxAssignments);
        var result = new ArrayList<Claimed>(claimed.size());
        for (var attempt : claimed) {
            var job = jobs.transition(attempt.jobId(), List.of(JobStatus.SCHEDULED), JobStatus.RUNNING)
                    .orElseThrow(() -> new IllegalStateException(
                            "Job " + attempt.jobId() + " of an assigned attempt is not SCHEDULED"));
            events.append(job.id(), attempt.id(), JobEventType.STARTED, Map.of("workerId", workerId.toString()));
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.convertValue(attempt.payload(), Map.class);
            result.add(new Claimed(
                    attempt.id(),
                    attempt.jobId(),
                    attempt.attemptNo(),
                    attempt.workloadType(),
                    payload,
                    attempt.timeoutSeconds()));
        }
        return result;
    }

    /**
     * Renews the leases of a heartbeating worker. Attempts the worker reports but no longer owns are returned as lost,
     * so it stops executing them; attempts whose jobs were cancelled are returned so it stops and reports CANCELLED.
     */
    @Transactional
    public Renewal renewLeases(UUID workerId, List<UUID> runningAttemptIds) {
        var renewed =
                new HashSet<>(attempts.renewLeases(workerId, runningAttemptIds, workerProperties.leaseDuration()));
        var renewedRunning =
                runningAttemptIds.stream().filter(renewed::contains).toList();
        var lost =
                runningAttemptIds.stream().filter(id -> !renewed.contains(id)).toList();
        var cancel =
                renewedRunning.isEmpty() ? List.<UUID>of() : attempts.findCancelRequested(workerId, renewedRunning);
        return new Renewal(renewedRunning, cancel, lost);
    }

    /** A worker's report. Fenced: only the worker that owns an active attempt can end it (invariants I6, I11). */
    @Transactional
    public ReportResult report(
            UUID workerId,
            UUID attemptId,
            AttemptOutcome outcome,
            @Nullable FailureClass failureClass,
            @Nullable String message,
            @Nullable Map<String, Object> result) {
        var locked = attempts.lock(attemptId).filter(a -> a.workerId().equals(workerId));
        if (locked.isEmpty()) {
            return new ReportResult.NotFound(attemptId);
        }
        var attempt = locked.get();
        var reportedStatus = switch (outcome) {
            case SUCCEEDED -> AttemptStatus.SUCCEEDED;
            case FAILED -> AttemptStatus.FAILED;
            case CANCELLED -> AttemptStatus.CANCELLED;
        };
        if (!attempt.status().isActive()) {
            var jobStatus = jobs.findById(attempt.jobId()).orElseThrow().status();
            // Delivery is at-least-once: a retried report of the outcome already recorded is answered, not refused.
            return attempt.status() == reportedStatus
                    ? new ReportResult.AlreadyRecorded(attemptId, attempt.status(), jobStatus)
                    : new ReportResult.Rejected(attemptId, attempt.status());
        }
        var serializedResult = result == null || result.isEmpty() ? null : json.writeValueAsString(result);
        var effectiveFailure =
                outcome == AttemptOutcome.FAILED && failureClass == null ? FailureClass.INTERNAL : failureClass;
        var jobStatus = endAttempt(attempt, reportedStatus, effectiveFailure, message, serializedResult);
        return new ReportResult.Applied(attemptId, reportedStatus, jobStatus);
    }

    /**
     * Recovers attempts whose leases expired: the worker is presumed lost, its reservation is released and the job is
     * retried or ends according to the retry policy. Each attempt is recovered exactly once (invariant I10).
     *
     * @return how many attempts were recovered
     */
    @Transactional
    public int recoverExpiredLeases(int limit) {
        var expired = attempts.lockExpired(limit);
        for (var attemptId : expired) {
            var attempt = attempts.lock(attemptId).orElseThrow();
            endAttempt(attempt, AttemptStatus.LOST, FailureClass.WORKER_LOST, "lease expired", null);
            log.atWarn()
                    .addKeyValue("attemptId", attemptId)
                    .addKeyValue("jobId", attempt.jobId())
                    .addKeyValue("workerId", attempt.workerId())
                    .log("Lease expired; attempt recovered");
        }
        return expired.size();
    }

    /** See {@link AttemptRepository#extendActiveLeases}. */
    @Transactional
    public int extendActiveLeasesAfterRestart() {
        return attempts.extendActiveLeases(workerProperties.leaseDuration());
    }

    public List<AttemptRepository.AttemptView> history(UUID jobId) {
        return attempts.findByJob(jobId);
    }

    private JobStatus endAttempt(
            LockedAttempt attempt,
            AttemptStatus endStatus,
            @Nullable FailureClass failureClass,
            @Nullable String message,
            @Nullable String result) {
        if (!attempt.status().canTransitionTo(endStatus)) {
            throw new IllegalStateException(
                    "Illegal attempt transition " + attempt.status() + " -> " + endStatus + " for " + attempt.id());
        }
        // Job row before the worker row: the global lock order.
        var job = jobs.lockById(attempt.jobId()).orElseThrow();
        var cancelRequested = job.cancelRequestedAt() != null;

        JobStatus nextJobStatus;
        String decisionText;
        RetryPolicy.Decision decision = null;
        if (endStatus == AttemptStatus.SUCCEEDED) {
            nextJobStatus = JobStatus.SUCCEEDED;
            decisionText = "succeeded";
        } else if (endStatus == AttemptStatus.CANCELLED) {
            nextJobStatus = JobStatus.CANCELLED;
            decisionText = "cancelled by the worker after a cancel request";
        } else {
            decision = retryPolicy.decide(
                    failureClass == null ? FailureClass.INTERNAL : failureClass,
                    attempt.attemptNo(),
                    job.maxAttempts(),
                    cancelRequested,
                    random);
            nextJobStatus = decision instanceof RetryPolicy.Decision.GiveUp(var terminal, var reason)
                    ? terminal
                    : JobStatus.RETRY_WAIT;
            decisionText = decision.describe();
        }

        attempts.finish(
                attempt.id(),
                endStatus,
                failureClass == null ? null : failureClass.name(),
                truncate(message),
                boundedResult(result),
                decisionText);
        capacity.release(attempt.workerId(), attempt.cpuMillis(), attempt.memoryMib(), attempt.accelerators());

        var delay = decision instanceof RetryPolicy.Decision.Retry(var retryDelay) ? retryDelay : null;
        jobs.applyAttemptOutcome(job.id(), job.status(), nextJobStatus, delay);
        appendOutcomeEvents(attempt, endStatus, failureClass, message, nextJobStatus, decisionText);
        return nextJobStatus;
    }

    private void appendOutcomeEvents(
            LockedAttempt attempt,
            AttemptStatus endStatus,
            @Nullable FailureClass failureClass,
            @Nullable String message,
            JobStatus nextJobStatus,
            String decisionText) {
        var details = new LinkedHashMap<String, Object>();
        details.put("attemptNo", attempt.attemptNo());
        details.put("workerId", attempt.workerId().toString());
        if (failureClass != null) {
            details.put("failureClass", failureClass.name());
        }
        if (message != null) {
            details.put("message", truncate(message));
        }
        switch (endStatus) {
            case SUCCEEDED -> events.append(attempt.jobId(), attempt.id(), JobEventType.SUCCEEDED, details);
            case CANCELLED -> events.append(attempt.jobId(), attempt.id(), JobEventType.CANCELLED, details);
            case FAILED, LOST -> {
                events.append(
                        attempt.jobId(),
                        attempt.id(),
                        endStatus == AttemptStatus.LOST ? JobEventType.ATTEMPT_LOST : JobEventType.ATTEMPT_FAILED,
                        details);
                var followUp = switch (nextJobStatus) {
                    case RETRY_WAIT -> JobEventType.RETRY_SCHEDULED;
                    case FAILED -> JobEventType.FAILED;
                    case DEAD -> JobEventType.DEAD;
                    case CANCELLED -> JobEventType.CANCELLED;
                    default -> throw new IllegalStateException("Unexpected job status after failure: " + nextJobStatus);
                };
                events.append(attempt.jobId(), attempt.id(), followUp, Map.of("decision", decisionText));
            }
            default -> throw new IllegalStateException("Attempt cannot end as " + endStatus);
        }
    }

    private static @Nullable String truncate(@Nullable String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** Oversized results are replaced by a marker instead of failing the report: the outcome matters more. */
    private @Nullable String boundedResult(@Nullable String result) {
        if (result == null || result.length() <= MAX_RESULT_BYTES) {
            return result;
        }
        return json.writeValueAsString(Map.of("truncated", true, "originalBytes", result.length()));
    }
}
