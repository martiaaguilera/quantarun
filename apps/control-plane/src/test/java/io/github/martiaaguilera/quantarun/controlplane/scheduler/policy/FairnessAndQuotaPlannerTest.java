package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Outcome;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.ProjectState;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FAIR_SHARE, DEADLINE and project quotas in the pure planner: no database, fully deterministic. */
class FairnessAndQuotaPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final UUID C = UUID.fromString("00000000-0000-7000-8000-0000000000cc");

    private int jobSequence;

    @Test
    void fairShare_alternatesBetweenEqualProjects_whateverTheirBacklog() {
        var jobs = new ArrayList<PendingJob>();
        jobs.addAll(jobs(A, 10));
        jobs.addAll(jobs(B, 3));

        var placed = placedProjects(plan(jobs, workers(6), Map.of(A, project(A, 1, 0), B, project(B, 1, 0)), 0));

        assertThat(placed).containsExactly(A, B, A, B, A, B);
    }

    @Test
    void fairShare_servesProjectsInProportionToTheirWeights() {
        var jobs = new ArrayList<PendingJob>();
        jobs.addAll(jobs(A, 40));
        jobs.addAll(jobs(B, 40));

        var placed = placedProjects(plan(jobs, workers(40), Map.of(A, project(A, 3, 0), B, project(B, 1, 0)), 0));

        // 3:1 up to the tie-break at equal virtual times, which goes to the lower project id (A).
        assertThat(placed).hasSize(40);
        assertThat(placed.stream().filter(A::equals).count()).isBetween(29L, 31L);
    }

    /** The brief's example: tenant A floods 10,000 jobs, tenant B sends 10. B must not wait behind all of A's. */
    @Test
    void fairShare_aFloodingProjectCannotStarveAnother() {
        var jobs = new ArrayList<PendingJob>();
        jobs.addAll(jobs(A, 10_000));
        // B submits after A's flood, so under FIFO all of A's jobs are older.
        for (int i = 0; i < 10; i++) {
            jobs.add(job(B, 50_000 + i, 0));
        }
        var projects = Map.of(A, project(A, 1, 0), B, project(B, 1, 0));

        var fair = placedProjects(plan(jobs, workers(20), projects, 0));
        var fifo = placedProjects(PlacementPlanner.plan(
                        new SchedulingSnapshot(NOW, jobs, workers(20), projects, 0), SchedulingPolicy.FIFO)
                .decisions());

        assertThat(fair).hasSize(20);
        assertThat(fair.stream().filter(B::equals).count())
                .as("B served under FAIR_SHARE")
                .isEqualTo(10);
        assertThat(fifo.stream().filter(B::equals).count())
                .as("B served under FIFO")
                .isZero();
    }

    /**
     * A project returning from a long idle period must not cash in that idle time: its virtual time is raised to the
     * system floor, so it shares with the busy project instead of taking every free slot.
     */
    @Test
    void fairShare_idleTimeCannotBeBankedAsCredit() {
        var jobs = new ArrayList<PendingJob>();
        jobs.addAll(jobs(A, 10));
        jobs.addAll(jobs(C, 10));
        var projects = Map.of(A, project(A, 1, 100.0), C, project(C, 1, 0.0));

        var withFloor = placedProjects(plan(jobs, workers(6), projects, 100.0));
        var withoutFloor = placedProjects(plan(jobs, workers(6), projects, 0.0));

        assertThat(withFloor.stream().filter(C::equals).count()).isEqualTo(3);
        assertThat(withoutFloor.stream().filter(C::equals).count())
                .as("without the floor C would take everything")
                .isEqualTo(6);
    }

    @Test
    void fairShare_chargesPlacements_andTheSystemClockNeverGoesBack() {
        var plan = PlacementPlanner.plan(
                new SchedulingSnapshot(NOW, jobs(A, 2), workers(4), Map.of(A, project(A, 2, 5.0)), 7.0),
                SchedulingPolicy.FAIR_SHARE);

        // Each job holds 1 of the fleet's 4 slots, its dominant share: 0.25 * 60 s / weight 2 = 7.5 per job.
        assertThat(plan.virtualTimes().get(A)).isEqualTo(7.0 + 2 * 7.5);
        assertThat(plan.systemVirtualTime()).isGreaterThanOrEqualTo(7.0);
        assertThat(plan.decisions().getFirst().reason()).contains("virtual time", "weight 2");
    }

    @ParameterizedTest
    @EnumSource(SchedulingPolicy.class)
    void runningQuota_holdsBackTheRest_withAReason(SchedulingPolicy policy) {
        var limited = new ProjectState(A, "alpha", 1, 0, 3, null, 1, 0);

        var plan = plan(policy, jobs(A, 5), workers(10), Map.of(A, limited), 0);

        assertThat(plan.decisions().stream().filter(d -> d.outcome() == Outcome.PLACED))
                .hasSize(2);
        assertThat(plan.decisions())
                .filteredOn(d -> d.outcome() == Outcome.WAITING_FOR_QUOTA)
                .hasSize(3)
                .allSatisfy(d -> assertThat(d.reason()).isEqualTo("Project alpha is at its quota of 3 running jobs"));
    }

    @Test
    void acceleratorQuota_countsAcceleratorsNotJobs() {
        var limited = new ProjectState(A, "alpha", 1, 0, null, 3, 0, 1);
        var gpuJobs = List.of(job(A, 0, 1), job(A, 1, 1), job(A, 2, 1), job(A, 3, 0));
        var gpuWorkers = List.of(worker(1, new Resources(64_000, 65_536, 8, 16)));

        var decisions = plan(SchedulingPolicy.FIFO, gpuJobs, gpuWorkers, Map.of(A, limited), 0)
                .decisions();

        assertThat(decisions)
                .extracting(PlacementDecision::outcome)
                .containsExactly(Outcome.PLACED, Outcome.PLACED, Outcome.WAITING_FOR_QUOTA, Outcome.PLACED);
        assertThat(decisions.get(2).reason()).contains("quota of 3 accelerators (3 in use, 1 requested)");
    }

    @Test
    void aJobThatCanNeverRun_isUnschedulable_evenWhenItsProjectIsAtQuota() {
        var full = new ProjectState(A, "alpha", 1, 0, 1, null, 1, 0);
        var tooBig = new PendingJob(nextJobId(), A, 4, NOW, null, new Demand(1_000_000, 1, 0), Set.of());

        var decision = plan(SchedulingPolicy.FIFO, List.of(tooBig), workers(4), Map.of(A, full), 0)
                .decisions()
                .getFirst();

        assertThat(decision.outcome()).isEqualTo(Outcome.UNSCHEDULABLE);
    }

    @Test
    void deadline_isEarliestDeadlineFirst_withUndatedJobsLast() {
        var late = dated(Duration.ofMinutes(30), 9);
        var soon = dated(Duration.ofMinutes(5), 0);
        var undated = new PendingJob(nextJobId(), A, 9, NOW.minusSeconds(600), null, new Demand(100, 64, 0), Set.of());
        var overdue = dated(Duration.ofMinutes(-1), 0);

        var decisions = plan(SchedulingPolicy.DEADLINE, List.of(late, undated, soon, overdue), workers(1), Map.of(), 0)
                .decisions();

        assertThat(decisions)
                .extracting(d -> d.job().id())
                .containsExactly(overdue.id(), soon.id(), late.id(), undated.id());
        assertThat(decisions.getFirst().reason()).contains("deadline passed 60s ago");
        assertThat(decisions.get(1).outcome()).isEqualTo(Outcome.WAITING_FOR_CAPACITY);
    }

    @Test
    void plans_areDeterministic() {
        var random = new SplittableRandom(11);
        var jobs = new ArrayList<PendingJob>();
        for (int i = 0; i < 300; i++) {
            var project = List.of(A, B, C).get(random.nextInt(3));
            jobs.add(new PendingJob(
                    UUID.nameUUIDFromBytes(("job" + i).getBytes()),
                    project,
                    random.nextInt(10),
                    NOW.minusSeconds(random.nextInt(600)),
                    random.nextBoolean() ? NOW.plusSeconds(random.nextInt(3600)) : null,
                    new Demand(100 + random.nextInt(900), 64, 0),
                    Set.of()));
        }
        var projects = Map.of(A, project(A, 1, 3), B, project(B, 2, 1), C, project(C, 5, 0));
        for (var policy : SchedulingPolicy.values()) {
            var first = plan(policy, jobs, workers(30), projects, 1);
            var second = plan(policy, new ArrayList<>(jobs.reversed()), workers(30), projects, 1);
            assertThat(second.decisions()).as("%s ignores input order", policy).isEqualTo(first.decisions());
            assertThat(second.virtualTimes()).isEqualTo(first.virtualTimes());
        }
    }

    /** Over random fleets, quotas and backlogs, no plan ever takes a project beyond its quotas. */
    @Test
    void quotas_areNeverExceeded_overRandomCases() {
        for (long seed = 0; seed < 1_000; seed++) {
            var random = new SplittableRandom(seed);
            var projects = new HashMap<UUID, ProjectState>();
            var jobs = new ArrayList<PendingJob>();
            for (var id : List.of(A, B, C)) {
                var maxRunning = random.nextBoolean() ? null : 1 + random.nextInt(5);
                var maxAccelerators = random.nextBoolean() ? null : random.nextInt(4);
                var running = maxRunning == null ? random.nextInt(3) : random.nextInt(maxRunning + 1);
                var accelerators = maxAccelerators == null ? 0 : random.nextInt(maxAccelerators + 1);
                projects.put(
                        id,
                        new ProjectState(
                                id,
                                "p",
                                1 + random.nextInt(4),
                                random.nextDouble() * 10,
                                maxRunning,
                                maxAccelerators,
                                running,
                                accelerators));
                for (int j = random.nextInt(15); j > 0; j--) {
                    jobs.add(job(id, random.nextInt(100), random.nextInt(3)));
                }
            }
            var fleet = List.of(
                    worker(1, new Resources(16_000, 32_768, 4, 8)), worker(2, new Resources(8_000, 16_384, 0, 8)));
            for (var policy : SchedulingPolicy.values()) {
                var plan = plan(policy, jobs, fleet, projects, 0);
                for (var project : projects.values()) {
                    var placedHere = plan.decisions().stream()
                            .filter(d -> d.outcome() == Outcome.PLACED
                                    && d.job().projectId().equals(project.id()))
                            .toList();
                    if (project.maxRunningJobs() != null) {
                        assertThat(project.runningJobs() + placedHere.size())
                                .as("seed %d, %s", seed, policy)
                                .isLessThanOrEqualTo(Math.max(project.maxRunningJobs(), project.runningJobs()));
                    }
                    if (project.maxAccelerators() != null) {
                        var accelerators = placedHere.stream()
                                .mapToInt(d -> d.job().demand().accelerators())
                                .sum();
                        assertThat(project.acceleratorsInUse() + accelerators)
                                .as("seed %d, %s", seed, policy)
                                .isLessThanOrEqualTo(Math.max(project.maxAccelerators(), project.acceleratorsInUse()));
                    }
                }
            }
        }
    }

    private PlacementPlanner.Plan plan(
            List<PendingJob> jobs, List<WorkerCandidate> workers, Map<UUID, ProjectState> projects, double system) {
        return plan(SchedulingPolicy.FAIR_SHARE, jobs, workers, projects, system);
    }

    private static PlacementPlanner.Plan plan(
            SchedulingPolicy policy,
            List<PendingJob> jobs,
            List<WorkerCandidate> workers,
            Map<UUID, ProjectState> projects,
            double system) {
        return PlacementPlanner.plan(new SchedulingSnapshot(NOW, jobs, workers, projects, system), policy);
    }

    private static List<UUID> placedProjects(PlacementPlanner.Plan plan) {
        return placedProjects(plan.decisions());
    }

    private static List<UUID> placedProjects(List<PlacementDecision> decisions) {
        return decisions.stream()
                .filter(d -> d.outcome() == Outcome.PLACED)
                .map(d -> d.job().projectId())
                .toList();
    }

    private List<PendingJob> jobs(UUID project, int count) {
        var jobs = new ArrayList<PendingJob>(count);
        for (int i = 0; i < count; i++) {
            jobs.add(job(project, i, 0));
        }
        return jobs;
    }

    private PendingJob job(UUID project, int ageSeconds, int accelerators) {
        return new PendingJob(
                nextJobId(),
                project,
                4,
                NOW.minusSeconds(100_000 - ageSeconds),
                null,
                new Demand(100, 64, accelerators),
                Set.of());
    }

    private PendingJob dated(Duration untilDeadline, int priority) {
        return new PendingJob(
                nextJobId(),
                A,
                priority,
                NOW.minusSeconds(60),
                NOW.plus(untilDeadline),
                new Demand(100, 64, 0),
                Set.of());
    }

    private UUID nextJobId() {
        return new UUID(0x01a0_0000_0000_7000L, ++jobSequence);
    }

    private static ProjectState project(UUID id, int weight, double virtualTime) {
        return new ProjectState(id, id.toString().substring(34), weight, virtualTime, null, null, 0, 0);
    }

    /** {@code slots} identical one-slot workers, with room for every job's CPU and memory. */
    private static List<WorkerCandidate> workers(int slots) {
        var workers = new ArrayList<WorkerCandidate>();
        for (int i = 1; i <= slots; i++) {
            workers.add(worker(i, new Resources(1_000, 1_024, 0, 1)));
        }
        return workers;
    }

    private static WorkerCandidate worker(int id, Resources capacity) {
        return new WorkerCandidate(
                new UUID(0x02a0_0000_0000_7000L, id), "w" + id, Set.of(), capacity, capacity, true, null);
    }
}
