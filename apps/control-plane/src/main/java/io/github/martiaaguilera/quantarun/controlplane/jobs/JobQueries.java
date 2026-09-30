package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Read side of the jobs module. Every read is scoped to what the caller may see. */
@Component
public class JobQueries {

    public static final int MAX_PAGE_SIZE = 200;

    private final JobRepository jobs;
    private final JobEventRepository events;

    JobQueries(JobRepository jobs, JobEventRepository events) {
        this.jobs = jobs;
        this.events = events;
    }

    /** A job of another project is reported as missing: its existence is not disclosed. */
    public Job get(Caller caller, UUID jobId) {
        return jobs.findById(jobId)
                .filter(job -> caller.canAccessProject(job.projectId()))
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    public List<Job> list(
            Caller caller,
            @Nullable UUID requestedProjectId,
            @Nullable JobStatus status,
            @Nullable UUID before,
            int limit) {
        var projectId = switch (caller) {
            case Caller.Admin _ -> requestedProjectId;
            case Caller.ProjectMember member -> member.projectId();
        };
        var bounded = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        return jobs.list(new JobRepository.ListFilter(projectId, status, before, bounded));
    }

    public List<JobEvent> events(Caller caller, UUID jobId) {
        get(caller, jobId);
        return events.findByJob(jobId);
    }
}
