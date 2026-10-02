package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SchedulingCycleTest {

    @Autowired
    SchedulingCycle cycle;

    @Autowired
    JobLifecycle lifecycle;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    UUID project;

    /** Scheduling sees every runnable job and live worker, so these tests start from an empty fleet and queue. */
    @BeforeEach
    void emptyQueueAndFleet() {
        jdbc.sql("TRUNCATE scheduler_decisions, job_events, job_attempts, jobs, worker_heartbeats, workers CASCADE")
                .update();
        project = jdbc.sql("INSERT INTO projects (name) VALUES (:n) RETURNING id")
                .param("n", "sched-" + UUID.randomUUID().toString().substring(0, 12))
                .query(UUID.class)
                .single();
    }

    @Test
    void cycle_placesAJobAtomically() {
        var worker = worker("w1", 4000, 8192, 0, 2, "ACTIVE");
        var job = submit(1000, 1024, 0, List.of());

        var result = cycle.runCycle(SchedulingPolicy.FIFO);

        assertThat(result.placed()).isEqualTo(1);
        var attempt = jdbc.sql("SELECT worker_id, status, attempt_no, cpu_millis FROM job_attempts WHERE job_id = :j")
                .param("j", job)
                .query((rs, n) -> List.of(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4)))
                .single();
        assertThat(attempt).containsExactly(worker, "ASSIGNED", 1, 1000);
        assertThat(jobStatus(job)).isEqualTo("SCHEDULED");
        assertThat(single("SELECT slots_reserved FROM workers WHERE id = :w", "w", worker))
                .isEqualTo(1L);
        assertThat(single("SELECT cpu_millis_reserved FROM workers WHERE id = :w", "w", worker))
                .isEqualTo(1000L);
        assertThat(single(
                        "SELECT count(*) FROM scheduler_decisions WHERE job_id = :j AND outcome = 'PLACED'", "j", job))
                .isEqualTo(1L);
        assertThat(single("SELECT count(*) FROM job_events WHERE job_id = :j AND type = 'SCHEDULED'", "j", job))
                .isEqualTo(1L);
        assertReservationsMatchActiveAttempts();
    }

    @Test
    void placedJob_isNotPlacedAgainByLaterCycles() {
        worker("w1", 4000, 8192, 0, 4, "ACTIVE");
        var job = submit(1000, 1024, 0, List.of());

        cycle.runCycle(SchedulingPolicy.FIFO);
        var second = cycle.runCycle(SchedulingPolicy.FIFO);

        assertThat(second.considered()).isZero();
        assertThat(single("SELECT count(*) FROM job_attempts WHERE job_id = :j", "j", job))
                .isEqualTo(1L);
    }

    @Test
    void unschedulableJob_isRecordedOnceNotEveryCycle() {
        worker("w1", 4000, 8192, 0, 4, "ACTIVE");
        var job = submit(1000, 1024, 2, List.of());

        for (int i = 0; i < 5; i++) {
            assertThat(cycle.runCycle(SchedulingPolicy.FIFO).unschedulable()).isEqualTo(1);
        }

        assertThat(single("SELECT count(*) FROM scheduler_decisions WHERE job_id = :j", "j", job))
                .isEqualTo(1L);
        var outcome = jdbc.sql("SELECT scheduling_outcome FROM jobs WHERE id = :j")
                .param("j", job)
                .query(String.class)
                .single();
        assertThat(outcome).isEqualTo("UNSCHEDULABLE");
        assertThat(jobStatus(job)).isEqualTo("QUEUED");
    }

    @Test
    void reasonChange_isRecordedWhenTheSituationChanges() {
        var job = submit(1000, 1024, 1, List.of("cuda"));
        cycle.runCycle(SchedulingPolicy.FIFO); // no workers at all
        worker("gpu", 4000, 8192, 1, 1, "ACTIVE");
        cycle.runCycle(SchedulingPolicy.FIFO); // now it fits

        var outcomes = jdbc.sql("SELECT outcome FROM scheduler_decisions WHERE job_id = :j ORDER BY id")
                .param("j", job)
                .query(String.class)
                .list();
        assertThat(outcomes).containsExactly("UNSCHEDULABLE", "PLACED");
    }

    @Test
    void drainingAndLateWorkers_receiveNoNewWork() {
        worker("draining", 4000, 8192, 0, 4, "DRAINING");
        var late = worker("late", 4000, 8192, 0, 4, "ACTIVE");
        jdbc.sql("UPDATE worker_heartbeats SET last_seen_at = now() - interval '10 seconds' WHERE worker_id = :w")
                .param("w", late)
                .update();
        var job = submit(1000, 1024, 0, List.of());

        var result = cycle.runCycle(SchedulingPolicy.FIFO);

        assertThat(result.placed()).isZero();
        assertThat(result.waiting()).isEqualTo(1);
        assertThat(jobStatus(job)).isEqualTo("QUEUED");
    }

    @Test
    void cancelledJobs_areNeverPlaced() {
        worker("w1", 4000, 8192, 0, 4, "ACTIVE");
        var job = submit(1000, 1024, 0, List.of());
        lifecycle.cancel(job);

        assertThat(cycle.runCycle(SchedulingPolicy.FIFO).considered()).isZero();
        assertThat(single("SELECT count(*) FROM job_attempts WHERE job_id = :j", "j", job))
                .isZero();
    }

    /**
     * Invariants I1, I2 and I3 under contention: 16 concurrent cycles compete for 400 jobs on 6 heterogeneous
     * workers. Every cycle locks jobs with SKIP LOCKED and workers with FOR UPDATE; whatever the interleaving, no
     * worker may be overcommitted, no job may hold two active attempts, and every reservation must be backed by
     * exactly the active attempts that made it.
     */
    @ParameterizedTest
    @EnumSource(SchedulingPolicy.class)
    void concurrentCycles_neverOvercommitOrDoublePlace(SchedulingPolicy policy) throws Exception {
        var totalSlots = 0;
        for (int i = 0; i < 6; i++) {
            var slots = 3 + i;
            totalSlots += slots;
            worker("node-" + i, 2000 + i * 1000, 4096 + i * 2048, i % 3, slots, "ACTIVE");
        }
        for (int i = 0; i < 400; i++) {
            submit(100 + (i % 7) * 50, 128 + (i % 5) * 64, i % 11 == 0 ? 1 : 0, List.of());
        }

        var errors = runConcurrently(16, () -> {
            for (int round = 0; round < 50; round++) {
                if (cycle.runCycle(policy).placed() == 0) {
                    break;
                }
            }
            return null;
        });

        assertThat(errors)
                .as("no cycle may fail, which would include a CHECK constraint violation")
                .isEmpty();
        var placed = single("SELECT count(*) FROM job_attempts WHERE status = 'ASSIGNED'", null, null);
        assertThat(placed).isPositive().isLessThanOrEqualTo(totalSlots);
        assertThat(single(
                        "SELECT count(*) FROM (SELECT job_id FROM job_attempts WHERE status IN ('ASSIGNED','RUNNING')"
                                + " GROUP BY job_id HAVING count(*) > 1) d",
                        null,
                        null))
                .as("a job with two active attempts (I3)")
                .isZero();
        assertThat(single("SELECT count(*) FROM jobs WHERE status = 'SCHEDULED'", null, null))
                .isEqualTo(placed);
        assertNoWorkerOvercommitted();
        assertReservationsMatchActiveAttempts();
    }

    private void assertNoWorkerOvercommitted() {
        var overcommitted = single("""
                SELECT count(*) FROM workers WHERE cpu_millis_reserved > cpu_millis_capacity
                    OR memory_mib_reserved > memory_mib_capacity OR accelerators_reserved > accelerator_capacity
                    OR slots_reserved > slot_capacity
                """, null, null);
        assertThat(overcommitted).as("overcommitted workers (I1)").isZero();
    }

    /** Invariant I2: every worker's reserved totals equal the sum over its active attempts. */
    private void assertReservationsMatchActiveAttempts() {
        var mismatches = single("""
                SELECT count(*) FROM workers w
                LEFT JOIN (
                    SELECT worker_id, sum(cpu_millis) cpu, sum(memory_mib) mem, sum(accelerators) acc, count(*) slots
                    FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING') GROUP BY worker_id
                ) a ON a.worker_id = w.id
                WHERE w.cpu_millis_reserved <> coalesce(a.cpu, 0)
                   OR w.memory_mib_reserved <> coalesce(a.mem, 0)
                   OR w.accelerators_reserved <> coalesce(a.acc, 0)
                   OR w.slots_reserved <> coalesce(a.slots, 0)
                """, null, null);
        assertThat(mismatches)
                .as("workers whose reservations disagree with their active attempts (I2)")
                .isZero();
    }

    private UUID worker(String name, int cpu, int memory, int accelerators, int slots, String lifecycle) {
        var labels = accelerators > 0 ? new String[] {"cuda"} : new String[0];
        var id = jdbc.sql("""
                        INSERT INTO workers (name, version, lifecycle, labels, cpu_millis_capacity, memory_mib_capacity,
                                             accelerator_capacity, slot_capacity, credential_prefix, credential_hash)
                        VALUES (:name, 'test', :lifecycle, :labels, :cpu, :memory, :acc, :slots, :prefix,
                                sha256(:prefix::bytea))
                        RETURNING id
                        """)
                .param("name", name)
                .param("lifecycle", lifecycle)
                .param("labels", labels)
                .param("cpu", cpu)
                .param("memory", memory)
                .param("acc", accelerators)
                .param("slots", slots)
                .param("prefix", UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class)
                .single();
        jdbc.sql("INSERT INTO worker_heartbeats (worker_id) VALUES (:id)")
                .param("id", id)
                .update();
        return id;
    }

    private UUID submit(int cpu, int memory, int accelerators, List<String> labels) {
        var submission = new JobSubmission(
                WorkloadType.DELAY,
                json.createObjectNode().put("durationMs", 10),
                4,
                new ResourceRequest(cpu, memory, accelerators),
                labels,
                3,
                60,
                null,
                null);
        return lifecycle.submit(project, submission, null).job().id();
    }

    private String jobStatus(UUID job) {
        return jdbc.sql("SELECT status FROM jobs WHERE id = :j")
                .param("j", job)
                .query(String.class)
                .single();
    }

    private long single(String sql, String param, UUID value) {
        var statement = jdbc.sql(sql);
        if (param != null) {
            statement = statement.param(param, value);
        }
        return statement.query(Long.class).single();
    }

    private static List<Throwable> runConcurrently(int threads, Callable<Void> task) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Void>>();
        var errors = new ArrayList<Throwable>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (var future : futures) {
                try {
                    future.get(120, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        }
        return errors;
    }
}
