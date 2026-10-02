package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.execution.ExecutionFixture;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * FAIR_SHARE, DEADLINE and project quotas through the real scheduling cycle on PostgreSQL 18 (invariants I16, I17).
 * Each test starts from an empty queue and fleet, as scheduling sees every runnable job and live worker.
 */
@IntegrationTest
class FairShareSchedulingTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    WorkerRegistry registry;

    @Autowired
    JobLifecycle lifecycle;

    @Autowired
    SchedulingCycle cycle;

    @Autowired
    JobAttempts attempts;

    ExecutionFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        jdbc.sql("TRUNCATE fair_share_clock").update();
        jdbc.sql("UPDATE fair_share_system SET virtual_time = 0").update();
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    /**
     * The brief's example on real rows: tenant A has 10,000 queued jobs before tenant B submits 10. Under FAIR_SHARE
     * B's jobs are placed in the very first cycle; under FIFO, B waits behind all 10,000.
     */
    @Test
    void floodingTenant_doesNotStarveAnother() {
        fixture.worker("big", 20);
        var flooder = project("flooder", 1, null);
        var light = project("light", 1, null);
        bulkQueue(flooder, 10_000);
        var lightJobs = submit(light, 10, null);

        var result = cycle.runCycle(SchedulingPolicy.FAIR_SHARE);

        assertThat(result.placed()).isEqualTo(20);
        assertThat(placedCount(light))
                .as("light project's jobs placed in the first cycle")
                .isEqualTo(10);
        lightJobs.forEach(job -> assertThat(fixture.jobStatus(job)).isEqualTo("SCHEDULED"));
    }

    @Test
    void underFifo_theSameFloodStarvesTheLightTenant() {
        fixture.worker("big", 20);
        var flooder = project("flooder", 1, null);
        var light = project("light", 1, null);
        bulkQueue(flooder, 10_000);
        submit(light, 10, null);

        cycle.runCycle(SchedulingPolicy.FIFO);

        assertThat(placedCount(light)).isZero();
    }

    /** Over many cycles with work completing in between, service follows the 3:1 weights. */
    @Test
    void weightsSetTheShareOfService_acrossCycles() {
        var worker = fixture.worker("shared", 8);
        var heavy = project("heavy", 3, null);
        var light = project("light", 1, null);
        bulkQueue(heavy, 400);
        bulkQueue(light, 400);

        for (int round = 0; round < 40; round++) {
            cycle.runCycle(SchedulingPolicy.FAIR_SHARE);
            completeEverything(worker);
        }

        var heavyServed = finishedCount(heavy);
        var lightServed = finishedCount(light);
        assertThat(heavyServed + lightServed).isEqualTo(320);
        var heavyShare = heavyServed / (double) (heavyServed + lightServed);
        assertThat(heavyShare).as("heavy project's share of service").isBetween(0.70, 0.80);
        var clocks = jdbc.sql("SELECT count(*) FROM fair_share_clock WHERE virtual_time > 0")
                .query(Long.class)
                .single();
        assertThat(clocks).isEqualTo(2);
    }

    @Test
    void deadlinePolicy_placesTheEarliestDeadlineFirst() {
        fixture.worker("single", 1);
        var team = project("team", 1, null);
        var now = Instant.now();
        var late = submitOne(team, now.plusSeconds(3600));
        var soon = submitOne(team, now.plusSeconds(60));
        var undated = submitOne(team, null);

        cycle.runCycle(SchedulingPolicy.DEADLINE);

        assertThat(fixture.jobStatus(soon)).isEqualTo("SCHEDULED");
        assertThat(fixture.jobStatus(late)).isEqualTo("QUEUED");
        assertThat(fixture.jobStatus(undated)).isEqualTo("QUEUED");
        var reason = jdbc.sql("SELECT reason FROM scheduler_decisions WHERE job_id = :j")
                .param("j", soon)
                .query(String.class)
                .single();
        assertThat(reason).contains("by DEADLINE", "deadline in");
    }

    @Test
    void runningQuota_holdsJobsBack_withARecordedReason() {
        fixture.worker("roomy", 20);
        var capped = project("capped", 1, 2);
        var jobs = submit(capped, 5, null);

        var result = cycle.runCycle(SchedulingPolicy.FIFO);

        assertThat(result.placed()).isEqualTo(2);
        var held = jdbc.sql("""
                        SELECT count(*) FROM jobs
                        WHERE id = ANY(:ids) AND status = 'QUEUED' AND scheduling_outcome = 'WAITING_FOR_QUOTA'
                          AND scheduling_reason LIKE 'Project capped-% is at its quota of 2 running jobs'
                        """)
                .param("ids", jobs.toArray(UUID[]::new))
                .query(Long.class)
                .single();
        assertThat(held).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM scheduler_decisions WHERE outcome = 'WAITING_FOR_QUOTA'")
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    /**
     * Invariant I17 under concurrency: 8 cycles at once, under different policies, round after round with work
     * finishing in between. The project never holds more active attempts than its quota.
     */
    @Test
    void runningQuota_holdsUnderConcurrentCycles() throws Exception {
        var workers = List.of(fixture.worker("w1", 20), fixture.worker("w2", 20), fixture.worker("w3", 20));
        var capped = project("capped", 1, 5);
        var other = project("other", 1, null);
        bulkQueue(capped, 200);
        bulkQueue(other, 200);
        var policies = SchedulingPolicy.values();
        var highest = 0;

        for (int round = 0; round < 10; round++) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) {
                var policy = policies[(round + i) % policies.length];
                tasks.add(() -> {
                    cycle.runCycle(policy);
                    return null;
                });
            }
            assertThat(runConcurrently(tasks)).as("errors in round %d", round).isEmpty();

            var active = activeAttempts(capped);
            assertThat(active).as("round %d", round).isLessThanOrEqualTo(5);
            highest = Math.max(highest, active);
            // Finish half of the capped project's work, so the next round has room to place again.
            for (var worker : workers) {
                for (var claimed : attempts.claim(worker.id(), 20)) {
                    if (claimed.attemptNo() == 1 && claimed.jobId().hashCode() % 2 == 0) {
                        attempts.report(
                                worker.id(), claimed.attemptId(), AttemptOutcome.SUCCEEDED, null, null, null, null);
                    }
                }
            }
        }
        assertThat(highest)
                .as("the quota was actually reached, not just never approached")
                .isEqualTo(5);
    }

    private int activeAttempts(UUID project) {
        return jdbc.sql("""
                        SELECT count(*) FROM job_attempts a JOIN jobs j ON j.id = a.job_id
                        WHERE j.project_id = :p AND a.status IN ('ASSIGNED', 'RUNNING')
                        """).param("p", project).query(Integer.class).single();
    }

    private void completeEverything(ExecutionFixture.RegisteredWorker worker) {
        for (var claimed : attempts.claim(worker.id(), 100)) {
            attempts.report(worker.id(), claimed.attemptId(), AttemptOutcome.SUCCEEDED, null, null, null, null);
        }
    }

    private UUID project(String name, int weight, Integer maxRunningJobs) {
        return jdbc.sql("""
                        INSERT INTO projects (name, weight, max_running_jobs) VALUES (:name, :weight, :max)
                        RETURNING id
                        """)
                .param("name", name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .param("weight", weight)
                .param("max", maxRunningJobs)
                .query(UUID.class)
                .single();
    }

    /** Queues many jobs in one statement; going through the API 10,000 times would only slow the test down. */
    private void bulkQueue(UUID project, int count) {
        jdbc.sql("""
                        INSERT INTO jobs (project_id, workload_type, payload, status, priority, cpu_millis, memory_mib,
                                          accelerators, max_attempts, timeout_seconds, available_at)
                        SELECT :project, 'delay', '{"durationMs": 10}', 'QUEUED', 4, 100, 64, 0, 3, 60,
                               now() - interval '1 hour' + g * interval '1 millisecond'
                        FROM generate_series(1, :count) g
                        """).param("project", project).param("count", count).update();
    }

    private List<UUID> submit(UUID project, int count, Instant deadline) {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            ids.add(submitOne(project, deadline));
        }
        return ids;
    }

    private UUID submitOne(UUID project, Instant deadline) {
        var submission = new JobSubmission(
                WorkloadType.DELAY,
                json.createObjectNode().put("durationMs", 10),
                4,
                new ResourceRequest(100, 64, 0),
                List.of(),
                3,
                60,
                null,
                deadline);
        return lifecycle.submit(project, submission, null).job().id();
    }

    private long placedCount(UUID project) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE project_id = :p AND status = 'SCHEDULED'")
                .param("p", project)
                .query(Long.class)
                .single();
    }

    private long finishedCount(UUID project) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE project_id = :p AND status = 'SUCCEEDED'")
                .param("p", project)
                .query(Long.class)
                .single();
    }

    private static List<Throwable> runConcurrently(List<Callable<Void>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Void>>();
        var errors = new ArrayList<Throwable>();
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            for (var task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (var future : futures) {
                try {
                    future.get(120, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        }
        return errors;
    }
}
