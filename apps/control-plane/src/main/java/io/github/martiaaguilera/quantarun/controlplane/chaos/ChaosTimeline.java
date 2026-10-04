package io.github.martiaaguilera.quantarun.controlplane.chaos;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEvent;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEventType;
import io.github.martiaaguilera.quantarun.controlplane.workers.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What happened after a fault was delivered, told from the records the system keeps anyway: the job events of every
 * job whose attempt was on the target worker around the fault, and the worker's later registrations.
 *
 * <p>Event times are database {@code now()}, the start of the transaction that wrote them. Across transactions racing
 * on one row the order of two events can be off by the length of a transaction (ENGINEERING_LOG, 2026-10-02), which is
 * far below what a recovery timeline measures. Within one job, events keep their id order.
 */
public record ChaosTimeline(
        @Nullable String targetLifecycle, List<Entry> entries, List<JobRecovery> jobs, Summary summary) {

    /** @param source EXPERIMENT, JOB or WORKER. */
    public record Entry(
            Instant at,
            String source,
            String type,
            @Nullable UUID jobId,
            @Nullable UUID attemptId,
            @Nullable UUID workerId,
            String detail) {}

    /**
     * @param disruptedAt the first lost or failed attempt at or after the fault fired.
     * @param recoveredAt the first success after the disruption.
     * @param detectionMs from the fault firing to the disruption being recorded.
     * @param recoveryMs from the fault firing to the job succeeding again.
     */
    public record JobRecovery(
            UUID jobId,
            String status,
            @Nullable Instant disruptedAt,
            @Nullable Instant recoveredAt,
            @Nullable Long detectionMs,
            @Nullable Long recoveryMs) {}

    public record Summary(
            int affectedJobs,
            int disruptedJobs,
            int recoveredJobs,
            @Nullable Long maxRecoveryMs) {}

    /** A job that had an attempt on the target worker during the fault's window, with its events in id order. */
    public record AffectedJob(UUID jobId, String status, List<JobEvent> events) {}

    private static final Set<JobEventType> DISRUPTIONS =
            EnumSet.of(JobEventType.ATTEMPT_LOST, JobEventType.ATTEMPT_FAILED);

    static ChaosTimeline build(
            ChaosExperiment experiment,
            @Nullable Worker target,
            List<AffectedJob> affected,
            List<Worker> laterRegistrations) {
        var entries = new ArrayList<Entry>();
        entries.add(experimentEntry(experiment.createdAt(), "CREATED", experiment, "Experiment created"));
        if (experiment.endedAt() != null) {
            entries.add(experimentEntry(
                    experiment.endedAt(),
                    experiment.status().name(),
                    experiment,
                    "Never delivered: the worker sent no heartbeat in time, or the experiment was cancelled"));
        }
        var jobs = new ArrayList<JobRecovery>();
        var delivered = experiment.deliveredAt();
        if (delivered != null) {
            entries.add(experimentEntry(delivered, "DELIVERED", experiment, "Fault delivered in a heartbeat response"));
            var firesAt = firesAt(experiment, delivered);
            if (!firesAt.equals(delivered)) {
                entries.add(experimentEntry(
                        firesAt,
                        "FIRES",
                        experiment,
                        "Fault scheduled to fire " + experiment.parameters().delayMs() + " ms after delivery"));
            }
            for (var job : affected) {
                job.events().stream()
                        .filter(event -> !event.occurredAt().isBefore(delivered))
                        .map(ChaosTimeline::jobEntry)
                        .forEach(entries::add);
                jobs.add(recovery(job, firesAt));
            }
            for (var registration : laterRegistrations) {
                entries.add(new Entry(
                        registration.registeredAt(),
                        "WORKER",
                        "REGISTERED",
                        null,
                        null,
                        registration.id(),
                        "Worker " + registration.name() + " registered again as a new member"));
            }
        }
        // Stable sort: entries at the same instant keep the order they were added in, so a job's events stay in id
        // order.
        entries.sort(Comparator.comparing(Entry::at));
        return new ChaosTimeline(
                target == null ? null : target.lifecycle().name(),
                List.copyOf(entries),
                List.copyOf(jobs),
                summary(jobs));
    }

    /** Only KILL_WORKER has a delay; every other fault takes effect as soon as the worker receives it. */
    static Instant firesAt(ChaosExperiment experiment, Instant delivered) {
        return delivered.plusMillis(experiment.parameters().delayMs());
    }

    private static JobRecovery recovery(AffectedJob job, Instant firesAt) {
        Instant disrupted = null;
        Instant recovered = null;
        for (var event : job.events()) {
            if (disrupted == null) {
                if (DISRUPTIONS.contains(event.type()) && !event.occurredAt().isBefore(firesAt)) {
                    disrupted = event.occurredAt();
                }
            } else if (event.type() == JobEventType.SUCCEEDED) {
                recovered = event.occurredAt();
                break;
            }
        }
        return new JobRecovery(
                job.jobId(),
                job.status(),
                disrupted,
                recovered,
                disrupted == null ? null : millisBetween(firesAt, disrupted),
                recovered == null ? null : millisBetween(firesAt, recovered));
    }

    private static Summary summary(List<JobRecovery> jobs) {
        var disrupted = jobs.stream().filter(job -> job.disruptedAt() != null).count();
        var recovered = jobs.stream().filter(job -> job.recoveredAt() != null).toList();
        var maxRecovery = recovered.stream()
                .map(JobRecovery::recoveryMs)
                .filter(ms -> ms != null)
                .max(Long::compare)
                .orElse(null);
        return new Summary(jobs.size(), (int) disrupted, recovered.size(), maxRecovery);
    }

    private static Entry experimentEntry(Instant at, String type, ChaosExperiment experiment, String detail) {
        return new Entry(at, "EXPERIMENT", type, experiment.jobId(), null, experiment.workerId(), detail);
    }

    private static Entry jobEntry(JobEvent event) {
        var details = event.details();
        var worker = details.hasNonNull("workerId")
                ? UUID.fromString(details.get("workerId").asString())
                : null;
        return new Entry(
                event.occurredAt(),
                "JOB",
                event.type().name(),
                event.jobId(),
                event.attemptId(),
                worker,
                details.toString());
    }

    private static long millisBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis();
    }
}
