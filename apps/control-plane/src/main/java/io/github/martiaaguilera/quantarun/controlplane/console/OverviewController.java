package io.github.martiaaguilera.quantarun.controlplane.console;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobQueries;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerResources;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's first screen in one request. Job numbers are scoped to the caller; the fleet is shared by every
 * project, so only an operator sees it.
 */
@RestController
class OverviewController {

    /**
     * @param successRate succeeded / (succeeded + failed + dead) over the last hour; null when nothing finished.
     * @param timeToStartP95Seconds 95th percentile from submission to start, over first attempts started in the last 15
     *     minutes. A job submitted with {@code notBefore} counts its deliberate wait too.
     */
    record JobOverview(
            Map<JobStatus, Long> byStatus,
            long queued,
            long running,
            long succeededLastHour,
            long failedLastHour,
            long deadLastHour,
            @Nullable Double successRate,
            long retriesLastHour,
            @Nullable Double timeToStartP95Seconds) {}

    /** @param utilization reserved / capacity per resource, 0 when the fleet offers none of it. */
    record FleetOverview(
            int healthyWorkers,
            int lateWorkers,
            int drainingWorkers,
            WorkerResources capacity,
            WorkerResources reserved,
            Map<String, Double> utilization) {}

    record Overview(JobOverview jobs, @Nullable FleetOverview fleet, Instant generatedAt) {}

    private final JobQueries jobs;
    private final WorkerRegistry workers;

    OverviewController(JobQueries jobs, WorkerRegistry workers) {
        this.jobs = jobs;
        this.workers = workers;
    }

    @GetMapping("/api/v1/overview")
    Overview overview(Caller caller) {
        var summary = jobs.summary(caller);
        var counts = summary.byStatus();
        var finished = summary.succeededLastHour() + summary.failedLastHour() + summary.deadLastHour();
        var jobOverview = new JobOverview(
                counts,
                counts.get(JobStatus.QUEUED) + counts.get(JobStatus.RETRY_WAIT),
                counts.get(JobStatus.SCHEDULED) + counts.get(JobStatus.RUNNING),
                summary.succeededLastHour(),
                summary.failedLastHour(),
                summary.deadLastHour(),
                finished == 0 ? null : summary.succeededLastHour() / (double) finished,
                summary.retriesLastHour(),
                summary.timeToStartP95Seconds());
        var fleet = caller instanceof Caller.Admin ? fleet() : null;
        return new Overview(jobOverview, fleet, Instant.now());
    }

    private FleetOverview fleet() {
        var fleet = workers.fleet();
        var capacity = fleet.capacity();
        var reserved = fleet.reserved();
        return new FleetOverview(
                fleet.healthy(),
                fleet.late(),
                fleet.draining(),
                capacity,
                reserved,
                Map.of(
                        "cpuMillis", ratio(reserved.cpuMillis(), capacity.cpuMillis()),
                        "memoryMib", ratio(reserved.memoryMib(), capacity.memoryMib()),
                        "accelerators", ratio(reserved.accelerators(), capacity.accelerators()),
                        "slots", ratio(reserved.slots(), capacity.slots())));
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0 : part / (double) whole;
    }
}
