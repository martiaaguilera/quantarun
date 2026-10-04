package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.AttemptRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.CheckpointRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Read side of the jobs module. Every read is scoped to what the caller may see. */
@Component
public class JobQueries {

    public static final int MAX_PAGE_SIZE = 200;

    private final JobRepository jobs;
    private final JobEventRepository events;
    private final AttemptRepository attempts;
    private final CheckpointRepository checkpoints;

    JobQueries(
            JobRepository jobs,
            JobEventRepository events,
            AttemptRepository attempts,
            CheckpointRepository checkpoints) {
        this.jobs = jobs;
        this.events = events;
        this.attempts = attempts;
        this.checkpoints = checkpoints;
    }

    /** A job of another project is reported as missing: its existence is not disclosed. */
    public Job get(Caller caller, UUID jobId) {
        return jobs.findById(jobId)
                .filter(job -> caller.canAccessProject(job.projectId()))
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    /** What a job list can be narrowed by; every field is optional. */
    public record JobFilter(
            @Nullable UUID projectId,
            @Nullable JobStatus status,
            @Nullable WorkloadType workloadType,
            @Nullable Integer priority,
            @Nullable UUID workerId,
            @Nullable Instant createdFrom,
            @Nullable Instant createdTo) {}

    /** A project member always sees its own project only, whatever the filter asks for. */
    public List<Job> list(Caller caller, JobFilter filter, @Nullable UUID before, int limit) {
        var projectId = switch (caller) {
            case Caller.Admin _ -> filter.projectId();
            case Caller.ProjectMember member -> member.projectId();
        };
        var bounded = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        return jobs.list(new JobRepository.ListFilter(
                projectId,
                filter.status(),
                filter.workloadType() == null ? null : filter.workloadType().wireName(),
                filter.priority(),
                filter.workerId(),
                filter.createdFrom(),
                filter.createdTo(),
                before,
                bounded));
    }

    public List<JobEvent> events(Caller caller, UUID jobId) {
        get(caller, jobId);
        return events.findByJob(jobId);
    }

    public List<AttemptRepository.AttemptView> attempts(Caller caller, UUID jobId) {
        get(caller, jobId);
        return attempts.findByJob(jobId);
    }

    /** Job counts and recent outcomes, over every project for an operator and over its own for a project. */
    public JobSummary summary(Caller caller) {
        var projectId = switch (caller) {
            case Caller.Admin _ -> null;
            case Caller.ProjectMember member -> member.projectId();
        };
        return jobs.summary(projectId);
    }

    /** The worker holding the job's active (assigned or running) attempt, if it has one. */
    public Optional<UUID> activeWorker(Caller caller, UUID jobId) {
        return attempts(caller, jobId).stream()
                .filter(attempt -> AttemptStatus.valueOf(attempt.status()).isActive())
                .map(AttemptRepository.AttemptView::workerId)
                .findFirst();
    }

    /** Operator read: the attempts a worker held during a time window, across every project. */
    public List<WorkerAttempt> attemptsOnWorker(
            Caller.Admin caller, UUID workerId, Instant from, Instant to, int limit) {
        return attempts.findOnWorkerDuring(workerId, from, to, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    public List<CheckpointRepository.Checkpoint> checkpoints(Caller caller, UUID jobId) {
        get(caller, jobId);
        return checkpoints.findAll(jobId);
    }
}
