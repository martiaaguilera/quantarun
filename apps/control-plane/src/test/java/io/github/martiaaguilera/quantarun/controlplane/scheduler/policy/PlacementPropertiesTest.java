package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Outcome;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Property-based checks of invariants I1 and I4 over thousands of random fleets and workloads, for every policy.
 *
 * <p>Hand-rolled rather than jqwik: jqwik 1.10 targets JUnit Platform 1.x, and Boot 4 ships Platform 6. Each case is
 * generated from its own seed, and a failure reports that seed, so any counterexample can be replayed exactly.
 */
class PlacementPropertiesTest {

    private static final int CASES = 2_000;
    private static final List<String> LABEL_POOL = List.of("cuda", "large-model", "arm", "ssd");
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @ParameterizedTest
    @EnumSource(SchedulingPolicy.class)
    void placementsNeverExceedFreeCapacityAndRespectCompatibility(SchedulingPolicy policy) {
        for (long seed = 1; seed <= CASES; seed++) {
            var snapshot = randomSnapshot(new SplittableRandom(seed));
            var plan = PlacementPlanner.planPlacements(snapshot, policy);
            try {
                assertInvariants(snapshot, plan);
            } catch (AssertionError e) {
                fail("Invariant violated for policy " + policy + " with seed " + seed + ": " + e.getMessage(), e);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SchedulingPolicy.class)
    void planIsIndependentOfInputOrder(SchedulingPolicy policy) {
        for (long seed = 1; seed <= CASES / 4; seed++) {
            var random = new SplittableRandom(seed);
            var snapshot = randomSnapshot(random);
            var jobs = new ArrayList<>(snapshot.jobs());
            var workers = new ArrayList<>(snapshot.workers());
            Collections.shuffle(jobs, new java.util.Random(seed));
            Collections.shuffle(workers, new java.util.Random(seed + 1));

            var original = PlacementPlanner.planPlacements(snapshot, policy);
            var shuffled = PlacementPlanner.planPlacements(new SchedulingSnapshot(NOW, jobs, workers), policy);

            assertThat(shuffled).as("seed %d", seed).isEqualTo(original);
        }
    }

    private static void assertInvariants(SchedulingSnapshot snapshot, List<PlacementDecision> plan) {
        assertThat(plan).hasSameSizeAs(snapshot.jobs());
        assertThat(plan.stream().map(d -> d.job().id()).distinct()).hasSameSizeAs(snapshot.jobs());

        var workersById = new HashMap<UUID, WorkerCandidate>();
        snapshot.workers().forEach(w -> workersById.put(w.id(), w));
        var remaining = new HashMap<UUID, Resources>();
        snapshot.workers().forEach(w -> remaining.put(w.id(), w.free()));

        for (var decision : plan) {
            var job = decision.job();
            assertThat(decision.candidates()).hasSizeLessThanOrEqualTo(PlacementPlanner.MAX_CANDIDATES_RECORDED);
            var compatibleLive = snapshot.workers().stream().anyMatch(w -> compatible(job, w));
            if (decision.outcome() == Outcome.PLACED) {
                var worker = workersById.get(decision.chosenWorkerId());
                assertThat(worker.acceptingWork())
                        .as("placed on a worker not accepting work")
                        .isTrue();
                assertThat(compatible(job, worker))
                        .as("placed on an incompatible worker (I4)")
                        .isTrue();
                var left = remaining.get(worker.id()).minus(job.demand());
                assertThat(left.cpuMillis()).as("CPU overcommitted (I1)").isNotNegative();
                assertThat(left.memoryMib()).as("memory overcommitted (I1)").isNotNegative();
                assertThat(left.accelerators())
                        .as("accelerators overcommitted (I1)")
                        .isNotNegative();
                assertThat(left.slots()).as("slots overcommitted (I1)").isNotNegative();
                remaining.put(worker.id(), left);
            } else {
                assertThat(decision.chosenWorkerId()).isNull();
                assertThat(decision.outcome() == Outcome.UNSCHEDULABLE)
                        .as("UNSCHEDULABLE exactly when no live worker could ever run the job")
                        .isEqualTo(!compatibleLive);
            }
        }
    }

    private static boolean compatible(PendingJob job, WorkerCandidate worker) {
        return worker.labels().containsAll(job.requiredLabels())
                && worker.capacity().covers(job.demand());
    }

    private static SchedulingSnapshot randomSnapshot(SplittableRandom random) {
        var workers = new ArrayList<WorkerCandidate>();
        var workerCount = random.nextInt(0, 7);
        for (int i = 0; i < workerCount; i++) {
            var capacity = new Resources(
                    random.nextInt(1, 17) * 500,
                    random.nextInt(1, 33) * 512,
                    random.nextInt(0, 4),
                    random.nextInt(1, 6));
            // Free capacity is anything between nothing and everything, so workers start partially loaded.
            var free = new Resources(
                    random.nextInt(0, capacity.cpuMillis() + 1),
                    random.nextInt(0, capacity.memoryMib() + 1),
                    random.nextInt(0, capacity.accelerators() + 1),
                    random.nextInt(0, capacity.slots() + 1));
            var accepting = random.nextInt(10) > 0;
            workers.add(new WorkerCandidate(
                    new UUID(1, i),
                    "w" + i,
                    randomLabels(random),
                    capacity,
                    free,
                    accepting,
                    accepting ? null : "worker is DRAINING"));
        }
        var jobs = new ArrayList<PendingJob>();
        var jobCount = random.nextInt(0, 40);
        for (int i = 0; i < jobCount; i++) {
            jobs.add(new PendingJob(
                    new UUID(2, i),
                    new UUID(3, random.nextInt(3)),
                    random.nextInt(10),
                    NOW.minusSeconds(random.nextInt(0, 3600)),
                    null,
                    new Demand(random.nextInt(1, 9) * 250, random.nextInt(1, 17) * 256, random.nextInt(0, 3)),
                    random.nextInt(4) == 0 ? randomLabels(random) : Set.of()));
        }
        return new SchedulingSnapshot(NOW, jobs, workers);
    }

    private static Set<String> randomLabels(SplittableRandom random) {
        var labels = new HashSet<String>();
        for (var label : LABEL_POOL) {
            if (random.nextInt(3) == 0) {
                labels.add(label);
            }
        }
        return labels;
    }
}
