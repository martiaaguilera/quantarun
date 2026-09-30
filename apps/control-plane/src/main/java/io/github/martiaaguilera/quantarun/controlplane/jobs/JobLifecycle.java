package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.SubmissionFingerprint;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Client-initiated job state changes: submission and cancellation. */
@Service
public class JobLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycle.class);

    /** How often cancel re-reads a job that moved between states mid-request before giving up. */
    private static final int CANCEL_ATTEMPTS = 3;

    public sealed interface SubmissionResult {
        Job job();

        record Created(Job job) implements SubmissionResult {}

        /** Same key and same request as an earlier submission: the original job, no new work. */
        record Replayed(Job job) implements SubmissionResult {}
    }

    public enum CancelOutcome {
        /** The job was waiting and is now CANCELLED. */
        CANCELLED,
        /** The job holds an attempt; its worker is asked to stop and the job ends CANCELLED when it does. */
        CANCEL_REQUESTED,
        /** Repeating a cancel is not an error. */
        ALREADY_CANCELLED
    }

    private final JobRepository jobs;
    private final JobEventRepository events;

    JobLifecycle(JobRepository jobs, JobEventRepository events) {
        this.jobs = jobs;
        this.events = events;
    }

    /**
     * Invariant I7/I8: one job per (project, idempotency key); a reused key with a different request is rejected.
     * The job row and its SUBMITTED event commit together.
     */
    @Transactional
    public SubmissionResult submit(UUID projectId, JobSubmission submission, @Nullable String idempotencyKey) {
        byte[] requestHash = idempotencyKey == null ? null : SubmissionFingerprint.of(submission);

        var inserted = jobs.insertIfAbsent(projectId, submission, idempotencyKey, requestHash);
        if (inserted.isPresent()) {
            var job = inserted.get();
            events.append(
                    job.id(),
                    null,
                    JobEventType.SUBMITTED,
                    Map.of("workloadType", job.workloadType().wireName(), "priority", job.priority()));
            log.atInfo()
                    .addKeyValue("jobId", job.id())
                    .addKeyValue("projectId", projectId)
                    .log("Job submitted");
            return new SubmissionResult.Created(job);
        }

        // The conflicting row belongs to a transaction that already committed (ON CONFLICT waited for it), and under
        // READ COMMITTED this new statement sees it.
        var existing = jobs.findByIdempotencyKey(projectId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency conflict reported but no job found for key in project " + projectId));
        if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request.");
        }
        return new SubmissionResult.Replayed(existing.job());
    }

    /**
     * Invariant I12: a cancelled job is never placed afterwards. Waiting jobs are cancelled with a conditional update;
     * jobs holding an attempt are flagged, and the attempt's end moves them to CANCELLED. The job can change state
     * between the two statements (for example, the scheduler places it), so a bounded loop re-evaluates.
     */
    @Transactional
    public CancelOutcome cancel(UUID jobId) {
        for (int attempt = 0; attempt < CANCEL_ATTEMPTS; attempt++) {
            if (jobs.transition(jobId, List.of(JobStatus.QUEUED, JobStatus.RETRY_WAIT), JobStatus.CANCELLED)
                    .isPresent()) {
                events.append(jobId, null, JobEventType.CANCELLED, Map.of("reason", "cancel requested by client"));
                return CancelOutcome.CANCELLED;
            }
            if (jobs.requestCancellation(jobId).isPresent()) {
                events.append(jobId, null, JobEventType.CANCEL_REQUESTED, Map.of());
                return CancelOutcome.CANCEL_REQUESTED;
            }
            var current = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            if (current.status() == JobStatus.CANCELLED) {
                return CancelOutcome.ALREADY_CANCELLED;
            }
            if (current.status().hasActiveAttempt() && current.cancelRequestedAt() != null) {
                return CancelOutcome.CANCEL_REQUESTED;
            }
            if (current.status().isFinal()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "JOB_NOT_CANCELLABLE",
                        "Job " + jobId + " already finished with status " + current.status() + ".");
            }
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "JOB_STATE_CHANGING",
                "Job " + jobId + " kept changing state during cancellation; retry the request.");
    }
}
