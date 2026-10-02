package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The outcome of replaying one scenario against several policies. */
public record SimulationResult(String scenario, long seed, int jobCount, List<PolicyResult> policies) {

    /**
     * @param resultHash SHA-256 over the deterministic part of the result (the metrics and every job's outcome, not
     *     the planning time). The same scenario, seed, job count and policy always produce the same hash (I15).
     * @param planning wall-clock planning cost, measured on this machine: useful, but not reproducible.
     */
    public record PolicyResult(SchedulingPolicy policy, Metrics metrics, PlanningTime planning, String resultHash) {}

    /**
     * Everything here is computed from simulated time and is reproducible.
     *
     * @param makespanMs simulated time from the first arrival to the last event.
     * @param throughputPerMinute succeeded jobs per simulated minute.
     * @param queueWaitMs from arrival to the first start, over jobs that started.
     * @param completionLatencyMs from arrival to success, over succeeded jobs.
     * @param deadlineMissRate among jobs with a deadline: the fraction that did not succeed by it.
     * @param starvationMs the longest wait for a first start, counting jobs that never started up to the end.
     * @param fairness Jain's index over each project's received / entitled service under weighted max-min fairness,
     *     while projects contended: 1 means every project got its fair share whenever it wanted it.
     * @param cycles scheduling cycles run.
     * @param projects each project's queue wait: where a policy puts the waiting, which totals hide.
     */
    public record Metrics(
            int succeeded,
            int failed,
            int dead,
            int neverFinished,
            int attempts,
            long makespanMs,
            double throughputPerMinute,
            Distribution queueWaitMs,
            Distribution completionLatencyMs,
            @Nullable Double deadlineMissRate,
            long starvationMs,
            Utilisation utilisation,
            @Nullable Double fairness,
            long cycles,
            List<ProjectWait> projects) {}

    public record ProjectWait(String project, int weight, Distribution queueWaitMs) {}

    public record Distribution(long count, double mean, long p50, long p95, long p99, long max) {

        static Distribution empty() {
            return new Distribution(0, 0, 0, 0, 0, 0);
        }
    }

    /** Time-weighted reserved fraction of the fleet's total capacity. */
    public record Utilisation(
            double cpu, double memory, @Nullable Double accelerators, double slots) {}

    public record PlanningTime(long cycles, double meanMicros, double p99Micros) {}

    /** One job's fate, the input to the result hash. */
    record JobOutcome(
            UUID jobId,
            String status,
            int attempts,
            @Nullable Long firstStartMs,
            @Nullable Long finishedMs) {}
}
