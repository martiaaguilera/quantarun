package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.SubmissionFingerprint;
import io.github.martiaaguilera.quantarun.controlplane.projects.Projects;
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
    private final Projects projects;

    JobLifecycle(JobRepository jobs, JobEventRepository events, Projects projects) {
        this.jobs = jobs;
        this.events = events;
        this.projects = projects;
    }

    /**
     * Invariant I7/I8: one job per (project, idempotency key); a reused key with a different request is rejected.
     * The job row and its SUBMITTED event commit together.
     */
    @Transactional
    public SubmissionResult submit(UUID projectId, JobSubmission submission, @Nullable String idempotencyKey) {
        byte[] requestHash = idempotencyKey == null ? null : SubmissionFingerprint.of(submission);
        var replay = admit(projectId, idempotencyKey, requestHash);
        if (replay != null) {
            return replay;
        }

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
        return replayOrConflict(existing, requestHash);
    }

    /**
     * Admission control against the project's {@code maxQueuedJobs}. Only a project with that quota pays for it: its
     * row is locked so concurrent submissions count one at a time, and the count and the insert that follows commit
     * together. A retried submission whose job already exists is replayed even at the quota: it adds no work.
     *
     * @return the replay of an existing job, or null to go on and insert
     */
    private @Nullable SubmissionResult admit(
            UUID projectId, @Nullable String idempotencyKey, byte @Nullable [] requestHash) {
        var unlocked = projects.findAll(List.of(projectId));
        if (unlocked.isEmpty() || unlocked.getFirst().maxQueuedJobs() == null) {
            return null;
        }
        var project = projects.lockForAdmission(projectId).orElseThrow();
        if (project.maxQueuedJobs() == null) {
            return null;
        }
        if (idempotencyKey != null) {
            var existing = jobs.findByIdempotencyKey(projectId, idempotencyKey);
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), requestHash);
            }
        }
        var unfinished = jobs.countUnfinished(projectId);
        if (unfinished >= project.maxQueuedJobs()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "QUOTA_EXCEEDED",
                    "Project " + project.name() + " already has " + unfinished + " unfinished jobs; its quota is "
                            + project.maxQueuedJobs() + ". Wait for some to finish or ask an operator to raise it.");
        }
        return null;
    }

    private static SubmissionResult replayOrConflict(JobRepository.IdempotencyRecord existing, byte[] requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request.");
        }
        return new SubmissionResult.Replayed(existing.job());
    }

    /** Matches the {@code jobs_revive_count_range} CHECK: a job that keeps dying needs a look, not a loop. */
    public static final int MAX_REVIVES = 10;

    /**
     * Invariant I13: a DEAD job runs again only through this explicit call. It gets a fresh attempt budget; its earlier
     * attempts and checkpoints stay, so a staged workload resumes after its last committed stage.
     */
    @Transactional
    public Job revive(UUID jobId) {
        var revived = jobs.revive(jobId, MAX_REVIVES);
        if (revived.isPresent()) {
            var job = revived.get();
            events.append(
                    jobId,
                    null,
                    JobEventType.REVIVED,
                    Map.of("reviveCount", job.reviveCount(), "attemptBudget", job.maxAttempts()));
            log.atInfo().addKeyValue("jobId", jobId).log("Job revived");
            return job;
        }
        var current = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (current.status() == JobStatus.DEAD) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REVIVE_LIMIT_REACHED",
                    "Job " + jobId + " was already revived " + MAX_REVIVES + " times.");
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "JOB_NOT_DEAD",
                "Only a DEAD job can be revived; job " + jobId + " is " + current.status() + ".");
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
