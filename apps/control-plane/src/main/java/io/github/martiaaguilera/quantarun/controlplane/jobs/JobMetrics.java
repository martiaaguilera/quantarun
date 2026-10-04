package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Job and attempt metrics. Every value is recorded after the transaction that caused it commits, so a rolled-back
 * submission or report is never counted. Tags are low-cardinality only (workload type, status, failure class,
 * decision): job, attempt, worker and project ids belong in logs and traces, where they cost nothing.
 */
@Component
class JobMetrics {

    /** What happened to the job after a failed or lost attempt. */
    enum Decision {
        NONE,
        RETRY,
        FINAL
    }

    private final MeterRegistry registry;

    JobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void submitted(WorkloadType type) {
        AfterCommit.run(() -> Counter.builder("quantarun.jobs.submitted")
                .description("Jobs admitted (a replayed idempotent submission is not counted)")
                .tag("workload_type", type.wireName())
                .register(registry)
                .increment());
    }

    /** From becoming runnable (submission, or the end of a retry backoff) to a worker starting the attempt. */
    void started(String workloadType, Duration queueWait) {
        AfterCommit.run(() -> Timer.builder("quantarun.jobs.queue.wait")
                .description("Time a runnable job waited for placement and claim")
                .tag("workload_type", workloadType)
                .publishPercentileHistogram()
                .register(registry)
                .record(queueWait));
    }

    /**
     * @param executed how long the attempt ran on its worker; null if it never started (lost or cancelled while
     *     still assigned).
     */
    void attemptEnded(
            WorkloadType type,
            AttemptStatus outcome,
            @Nullable String failureClass,
            Decision decision,
            @Nullable Duration executed) {
        AfterCommit.run(() -> {
            Counter.builder("quantarun.attempts.ended")
                    .description("Attempts that ended, by outcome and what was decided next")
                    .tag("workload_type", type.wireName())
                    .tag("outcome", outcome.name())
                    .tag("failure_class", failureClass == null ? "none" : failureClass)
                    .tag("decision", decision.name().toLowerCase())
                    .register(registry)
                    .increment();
            if (executed != null) {
                Timer.builder("quantarun.attempts.execution")
                        .description("Time from a worker starting an attempt to its end")
                        .tag("workload_type", type.wireName())
                        .tag("outcome", outcome.name())
                        .publishPercentileHistogram()
                        .register(registry)
                        .record(executed);
            }
            if (outcome == AttemptStatus.LOST) {
                Counter.builder("quantarun.leases.expired")
                        .description("Attempts recovered because their lease expired")
                        .register(registry)
                        .increment();
            }
        });
    }

    /**
     * A job reached a final status. A job with a deadline missed it unless it succeeded by then; a cancelled job did
     * not miss anything, since nobody wants its result any more.
     */
    void finished(Job job, JobStatus status, Instant finishedAt) {
        var missed = job.deadlineAt() != null
                && status != JobStatus.CANCELLED
                && !(status == JobStatus.SUCCEEDED && !finishedAt.isAfter(job.deadlineAt()));
        AfterCommit.run(() -> {
            Counter.builder("quantarun.jobs.finished")
                    .description("Jobs that reached a final status")
                    .tag("workload_type", job.workloadType().wireName())
                    .tag("status", status.name())
                    .register(registry)
                    .increment();
            if (missed) {
                Counter.builder("quantarun.jobs.deadline.missed")
                        .description("Jobs with a deadline that did not succeed by it")
                        .tag("workload_type", job.workloadType().wireName())
                        .register(registry)
                        .increment();
            }
        });
    }
}
