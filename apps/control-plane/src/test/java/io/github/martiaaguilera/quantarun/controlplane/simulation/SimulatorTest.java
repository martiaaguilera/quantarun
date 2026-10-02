package io.github.martiaaguilera.quantarun.controlplane.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.PolicyResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The simulator as pure code: no Spring, no database, no clock. */
class SimulatorTest {

    private static final List<SchedulingPolicy> ALL = List.of(SchedulingPolicy.values());

    /** Invariant I15: the same scenario, seed and policy produce an identical result, every time. */
    @ParameterizedTest
    @EnumSource(Scenario.class)
    void sameSeed_givesAnIdenticalResult_forEveryPolicy(Scenario scenario) {
        var first = Simulations.run(scenario, 42, 400, ALL);
        var second = Simulations.run(scenario, 42, 400, ALL);

        for (int i = 0; i < ALL.size(); i++) {
            assertThat(second.policies().get(i).resultHash())
                    .as("%s under %s", scenario, ALL.get(i))
                    .isEqualTo(first.policies().get(i).resultHash());
            assertThat(second.policies().get(i).metrics())
                    .isEqualTo(first.policies().get(i).metrics());
        }
        assertThat(scenario.trace(42, 400)).isEqualTo(scenario.trace(42, 400));
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void anotherSeed_givesAnotherWorkload(Scenario scenario) {
        var one = Simulations.run(scenario, 1, 300, List.of(SchedulingPolicy.FIFO));
        var other = Simulations.run(scenario, 2, 300, List.of(SchedulingPolicy.FIFO));

        assertThat(other.policies().getFirst().resultHash())
                .isNotEqualTo(one.policies().getFirst().resultHash());
    }

    /** Every job ends somewhere, and nothing overcommits a worker (the simulator throws if the planner ever would). */
    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyJobIsAccountedFor_underEveryPolicy(Scenario scenario) {
        var result = Simulations.run(scenario, 7, 500, ALL);

        for (var policy : result.policies()) {
            var metrics = policy.metrics();
            assertThat(metrics.succeeded() + metrics.failed() + metrics.dead() + metrics.neverFinished())
                    .as("%s under %s", scenario, policy.policy())
                    .isEqualTo(500);
            assertThat(metrics.attempts()).isGreaterThanOrEqualTo(metrics.succeeded());
            assertThat(metrics.utilisation().slots()).isBetween(0.0, 1.0);
            assertThat(metrics.queueWaitMs().p50())
                    .isLessThanOrEqualTo(metrics.queueWaitMs().p95());
            assertThat(metrics.queueWaitMs().p95())
                    .isLessThanOrEqualTo(metrics.queueWaitMs().p99());
        }
    }

    /**
     * The trade-off the brief asks to show. Fair share is fairer and protects the quiet tenants, and the waiting does
     * not disappear: it moves onto the tenant that flooded the queue, whose jobs now wait longer than under FIFO.
     */
    @Test
    void noisyNeighbor_fairShareProtectsTheQuietTenants_atTheNoisyOnesExpense() {
        var result = Simulations.run(
                Scenario.NOISY_NEIGHBOR, 42, 1_000, List.of(SchedulingPolicy.FIFO, SchedulingPolicy.FAIR_SHARE));
        var fifo = metrics(result, SchedulingPolicy.FIFO);
        var fair = metrics(result, SchedulingPolicy.FAIR_SHARE);

        assertThat(fair.fairness()).isGreaterThan(fifo.fairness());
        for (var quiet : List.of("tenant-b", "tenant-c")) {
            assertThat(waitP95(fair, quiet)).as(quiet).isLessThan(waitP95(fifo, quiet));
        }
        assertThat(waitP95(fair, "tenant-a")).as("the noisy tenant pays").isGreaterThan(waitP95(fifo, "tenant-a"));
    }

    private static long waitP95(SimulationResult.Metrics metrics, String project) {
        return metrics.projects().stream()
                .filter(p -> p.project().equals(project))
                .findFirst()
                .orElseThrow()
                .queueWaitMs()
                .p95();
    }

    @Test
    void deadlineHeavy_edfMissesFewerDeadlinesThanFifo() {
        var result = Simulations.run(
                Scenario.DEADLINE_HEAVY, 42, 1_000, List.of(SchedulingPolicy.FIFO, SchedulingPolicy.DEADLINE));

        assertThat(metrics(result, SchedulingPolicy.DEADLINE).deadlineMissRate())
                .isLessThan(metrics(result, SchedulingPolicy.FIFO).deadlineMissRate());
    }

    /** Work on the crashed worker is lost, recovered after the lease, and retried; nothing is lost for good. */
    @Test
    void workerFailure_lostAttemptsAreRetriedElsewhere() {
        var result = Simulations.run(Scenario.WORKER_FAILURE, 42, 600, List.of(SchedulingPolicy.LEAST_LOADED));
        var metrics = result.policies().getFirst().metrics();

        assertThat(metrics.attempts()).as("some attempts were lost and retried").isGreaterThan(600);
        assertThat(metrics.succeeded()).isEqualTo(600);
    }

    @Test
    void rateLimit_retriesTheRateLimitedJobs_andTheTransientOnesUpToTheirBudget() {
        var metrics = Simulations.run(Scenario.RATE_LIMIT, 42, 1_000, List.of(SchedulingPolicy.FIFO))
                .policies()
                .getFirst()
                .metrics();

        assertThat(metrics.attempts()).isGreaterThan(1_300);
        // Jobs failing twice still succeed on the third and last attempt of their budget of 3.
        assertThat(metrics.succeeded()).isEqualTo(1_000);
    }

    @Test
    void theLargestTraceSimulatesInSeconds() {
        var started = System.nanoTime();

        var result = Simulations.run(Scenario.STEADY, 1, Scenario.MAX_JOBS, List.of(SchedulingPolicy.FAIR_SHARE));

        assertThat(result.policies().getFirst().metrics().succeeded()).isEqualTo(Scenario.MAX_JOBS);
        assertThat((System.nanoTime() - started) / 1_000_000_000.0).isLessThan(30);
    }

    private static SimulationResult.Metrics metrics(SimulationResult result, SchedulingPolicy policy) {
        return result.policies().stream()
                .filter(p -> p.policy() == policy)
                .map(PolicyResult::metrics)
                .findFirst()
                .orElseThrow();
    }
}
