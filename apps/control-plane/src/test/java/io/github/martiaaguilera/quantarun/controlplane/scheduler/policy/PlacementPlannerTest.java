package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Outcome;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Verdict;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlacementPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-7000-8000-00000000000a");

    // Ids are chosen so their order is obvious: worker ids sort by their last digit.
    private static final WorkerCandidate CPU = worker(1, "cpu", Set.of(), new Resources(4000, 8192, 0, 4));
    private static final WorkerCandidate MIXED = worker(2, "mixed", Set.of("cuda"), new Resources(8000, 16384, 1, 4));
    private static final WorkerCandidate ACCEL =
            worker(3, "accel", Set.of("cuda", "large-model"), new Resources(8000, 32768, 2, 2));

    @Test
    void fifo_placesOldestFirstOnTheFirstWorkerThatFits() {
        var older = job(1, 0, 1000, 512, 0, Set.of());
        var newer = job(2, 10, 1000, 512, 0, Set.of());

        var plan = PlacementPlanner.planPlacements(snapshot(List.of(newer, older), CPU, MIXED), SchedulingPolicy.FIFO);

        assertThat(plan).extracting(d -> d.job().id()).containsExactly(older.id(), newer.id());
        assertThat(plan).allSatisfy(d -> assertThat(d.chosenWorkerId()).isEqualTo(CPU.id()));
    }

    @Test
    void requiredLabels_restrictPlacementAndAreExplained() {
        var cudaJob = job(1, 0, 1000, 512, 0, Set.of("cuda"));

        var decision = PlacementPlanner.planPlacements(snapshot(List.of(cudaJob), CPU, MIXED), SchedulingPolicy.FIFO)
                .getFirst();

        assertThat(decision.chosenWorkerId()).isEqualTo(MIXED.id());
        assertThat(decision.candidates())
                .filteredOn(c -> c.workerId().equals(CPU.id()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.verdict()).isEqualTo(Verdict.MISSING_LABELS);
                    assertThat(c.detail()).contains("cuda");
                });
        assertThat(decision.candidates().getFirst().verdict()).isEqualTo(Verdict.CHOSEN);
    }

    @Test
    void jobLargerThanEveryWorker_isUnschedulableNotWaiting() {
        var huge = job(1, 0, 1000, 512, 4, Set.of());

        var decision = PlacementPlanner.planPlacements(
                        snapshot(List.of(huge), CPU, MIXED, ACCEL), SchedulingPolicy.FIFO)
                .getFirst();

        assertThat(decision.outcome()).isEqualTo(Outcome.UNSCHEDULABLE);
        assertThat(decision.reason()).contains("large enough").contains("accelerators");
    }

    @Test
    void missingLabelsEverywhere_isUnschedulableWithTheLabelsNamed() {
        var exotic = job(1, 0, 1000, 512, 0, Set.of("tpu"));

        var decision = PlacementPlanner.planPlacements(snapshot(List.of(exotic), CPU, ACCEL), SchedulingPolicy.FIFO)
                .getFirst();

        assertThat(decision.outcome()).isEqualTo(Outcome.UNSCHEDULABLE);
        assertThat(decision.reason()).contains("[tpu]");
    }

    @Test
    void compatibleButFull_isWaitingForCapacity() {
        var full = worker(1, "full", Set.of(), new Resources(4000, 8192, 0, 4), new Resources(500, 8192, 0, 4));
        var job = job(1, 0, 1000, 512, 0, Set.of());

        var decision = PlacementPlanner.planPlacements(snapshot(List.of(job), full), SchedulingPolicy.FIFO)
                .getFirst();

        assertThat(decision.outcome()).isEqualTo(Outcome.WAITING_FOR_CAPACITY);
        assertThat(decision.reason()).contains("needs 1000m CPU, 500m free");
    }

    @Test
    void drainingWorker_countsAsCompatibleButReceivesNothing() {
        var draining = new WorkerCandidate(
                CPU.id(), "draining", Set.of(), CPU.capacity(), CPU.capacity(), false, "worker is DRAINING");
        var job = job(1, 0, 1000, 512, 0, Set.of());

        var decision = PlacementPlanner.planPlacements(snapshot(List.of(job), draining), SchedulingPolicy.FIFO)
                .getFirst();

        assertThat(decision.outcome()).isEqualTo(Outcome.WAITING_FOR_CAPACITY);
        assertThat(decision.candidates().getFirst().verdict()).isEqualTo(Verdict.NOT_ACCEPTING_WORK);
    }

    @Test
    void capacityPromisedEarlierInThePlan_isNotPromisedAgain() {
        var twoSlots = worker(1, "small", Set.of(), new Resources(8000, 8192, 0, 2));
        var jobs = List.of(
                job(1, 0, 100, 64, 0, Set.of()), job(2, 1, 100, 64, 0, Set.of()), job(3, 2, 100, 64, 0, Set.of()));

        var plan = PlacementPlanner.planPlacements(snapshot(jobs, twoSlots), SchedulingPolicy.FIFO);

        assertThat(plan)
                .extracting(PlacementDecision::outcome)
                .containsExactly(Outcome.PLACED, Outcome.PLACED, Outcome.WAITING_FOR_CAPACITY);
    }

    @Test
    void largeJobThatDoesNotFit_doesNotBlockSmallerJobsBehindIt() {
        var worker = worker(1, "w", Set.of(), new Resources(4000, 8192, 0, 4), new Resources(1000, 8192, 0, 4));
        var big = job(1, 0, 3000, 512, 0, Set.of());
        var small = job(2, 5, 500, 512, 0, Set.of());

        var plan = PlacementPlanner.planPlacements(snapshot(List.of(big, small), worker), SchedulingPolicy.FIFO);

        assertThat(plan)
                .extracting(PlacementDecision::outcome)
                .containsExactly(Outcome.WAITING_FOR_CAPACITY, Outcome.PLACED);
    }

    @Test
    void priority_givesScarceCapacityToTheMostUrgentJob() {
        var oneSlot = worker(1, "w", Set.of(), new Resources(4000, 8192, 0, 1));
        var oldLowPriority = job(1, 0, 100, 64, 0, Set.of(), 1);
        var newUrgent = job(2, 60, 100, 64, 0, Set.of(), 9);

        var plan = PlacementPlanner.planPlacements(
                snapshot(List.of(oldLowPriority, newUrgent), oneSlot), SchedulingPolicy.PRIORITY);

        assertThat(plan.getFirst().job().id()).isEqualTo(newUrgent.id());
        assertThat(plan.getFirst().outcome()).isEqualTo(Outcome.PLACED);
        assertThat(plan.get(1).outcome()).isEqualTo(Outcome.WAITING_FOR_CAPACITY);
    }

    @Test
    void leastLoaded_spreadsWorkAcrossEqualWorkers() {
        var a = worker(1, "a", Set.of(), new Resources(4000, 8192, 0, 4));
        var b = worker(2, "b", Set.of(), new Resources(4000, 8192, 0, 4));
        var jobs = List.of(job(1, 0, 1000, 1024, 0, Set.of()), job(2, 1, 1000, 1024, 0, Set.of()));

        var plan = PlacementPlanner.planPlacements(snapshot(jobs, a, b), SchedulingPolicy.LEAST_LOADED);

        assertThat(plan).extracting(PlacementDecision::chosenWorkerId).containsExactly(a.id(), b.id());
    }

    @Test
    void binPacking_fillsOneWorkerBeforeTheNext() {
        var a = worker(1, "a", Set.of(), new Resources(4000, 8192, 0, 4));
        var b = worker(2, "b", Set.of(), new Resources(4000, 8192, 0, 4));
        var jobs = List.of(job(1, 0, 1000, 1024, 0, Set.of()), job(2, 1, 1000, 1024, 0, Set.of()));

        var plan = PlacementPlanner.planPlacements(snapshot(jobs, a, b), SchedulingPolicy.BIN_PACKING);

        assertThat(plan).extracting(PlacementDecision::chosenWorkerId).containsExactly(a.id(), a.id());
    }

    @Test
    void binPacking_keepsAcceleratorWorkersForAcceleratorJobs() {
        // The accelerator worker is the tightest fit by utilisation, but a CPU-only job there could block a GPU job.
        var cpuOnly = job(1, 0, 1000, 1024, 0, Set.of());
        var gpu = job(2, 1, 1000, 1024, 2, Set.of("cuda"));

        var plan = PlacementPlanner.planPlacements(
                snapshot(List.of(cpuOnly, gpu), CPU, ACCEL), SchedulingPolicy.BIN_PACKING);

        assertThat(plan.get(0).chosenWorkerId()).isEqualTo(CPU.id());
        assertThat(plan.get(1).chosenWorkerId()).isEqualTo(ACCEL.id());
    }

    @Test
    void sameSnapshot_alwaysProducesTheSamePlan() {
        var jobs = List.of(
                job(1, 0, 1000, 512, 0, Set.of()),
                job(2, 0, 1000, 512, 1, Set.of("cuda")),
                job(3, 0, 3000, 512, 0, Set.of()));
        var first = PlacementPlanner.planPlacements(snapshot(jobs, CPU, MIXED, ACCEL), SchedulingPolicy.LEAST_LOADED);
        var second = PlacementPlanner.planPlacements(
                snapshot(List.of(jobs.get(2), jobs.get(0), jobs.get(1)), ACCEL, CPU, MIXED),
                SchedulingPolicy.LEAST_LOADED);

        assertThat(second).isEqualTo(first);
    }

    static WorkerCandidate worker(int n, String name, Set<String> labels, Resources capacity) {
        return worker(n, name, labels, capacity, capacity);
    }

    static WorkerCandidate worker(int n, String name, Set<String> labels, Resources capacity, Resources free) {
        return new WorkerCandidate(id(n), name, labels, capacity, free, true, null);
    }

    static PendingJob job(int n, int ageOffsetSeconds, int cpu, int memory, int accelerators, Set<String> labels) {
        return job(n, ageOffsetSeconds, cpu, memory, accelerators, labels, 4);
    }

    static PendingJob job(
            int n, int ageOffsetSeconds, int cpu, int memory, int accelerators, Set<String> labels, int priority) {
        return new PendingJob(
                UUID.fromString("00000000-0000-7000-9000-%012d".formatted(n)),
                PROJECT,
                priority,
                NOW.minusSeconds(100).plusSeconds(ageOffsetSeconds),
                null,
                new Demand(cpu, memory, accelerators),
                labels);
    }

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(n));
    }

    private static SchedulingSnapshot snapshot(List<PendingJob> jobs, WorkerCandidate... workers) {
        return new SchedulingSnapshot(NOW, jobs, List.of(workers));
    }
}
