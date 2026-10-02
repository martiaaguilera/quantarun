package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
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
 * The Phase 6 races on real PostgreSQL 18: cancellation against the real scheduler, against a retry, and against a
 * failing attempt (invariant I12), and the retry budget with schedulers, failures and the reaper all running at once
 * (invariant I9).
 */
@IntegrationTest
class FailureRacesTest {

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
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    @Test
    void cancelRacingTheRealScheduler_neverLosesTheCancellation() throws Exception {
        for (int round = 0; round < 5; round++) {
            fixture.reset();
            var workers = List.of(fixture.worker("w1", 30), fixture.worker("w2", 30));
            var jobs = submitMany(60, 3);

            raceSchedulersAgainstCancels(jobs);

            assertEveryCancelTookEffect(jobs);
            finishCooperativeCancels(workers);
            assertAllCancelledAndNeverPlacedAgain(jobs);
        }
    }

    @Test
    void cancelRacingARetryPlacement_neverLosesTheCancellation() throws Exception {
        for (int round = 0; round < 5; round++) {
            fixture.reset();
            var workers = List.of(fixture.worker("w1", 30), fixture.worker("w2", 30));
            var jobs = submitMany(40, 3);
            // First attempts fail, so every job waits in RETRY_WAIT when the race starts.
            while (fixture.place() > 0) {}
            for (var worker : workers) {
                for (var claimed : attempts.claim(worker.id(), 30)) {
                    attempts.report(
                            worker.id(),
                            claimed.attemptId(),
                            AttemptOutcome.FAILED,
                            FailureClass.TRANSIENT,
                            "x",
                            null,
                            null);
                }
            }
            jobs.forEach(job -> assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT"));
            jobs.forEach(fixture::makeRunnableNow);

            raceSchedulersAgainstCancels(jobs);

            assertEveryCancelTookEffect(jobs);
            finishCooperativeCancels(workers);
            assertAllCancelledAndNeverPlacedAgain(jobs);
        }
    }

    /**
     * A cancel request and a failure report arrive together. If the report wins, the job is in RETRY_WAIT and the
     * cancel ends it directly; if the cancel wins, the job is flagged and the failure's retry decision sees the flag.
     * Either way the job ends CANCELLED and is never retried.
     */
    @Test
    void cancelRacingAFailureReport_alwaysEndsCancelled() throws Exception {
        var worker = fixture.worker("w", 1);
        for (int round = 0; round < 40; round++) {
            var job = fixture.submit(3);
            assertThat(fixture.place()).isEqualTo(1);
            var attempt = fixture.latestAttempt(job);
            attempts.claim(worker.id(), 1);

            var errors = runConcurrently(List.of(
                    () -> {
                        lifecycle.cancel(job);
                        return null;
                    },
                    () -> {
                        attempts.report(
                                worker.id(), attempt, AttemptOutcome.FAILED, FailureClass.TRANSIENT, "x", null, null);
                        return null;
                    }));

            assertThat(errors).isEmpty();
            assertThat(fixture.jobStatus(job)).as("round %d", round).isEqualTo("CANCELLED");
            fixture.makeRunnableNow(job);
            assertThat(fixture.place()).isZero();
        }
    }

    /**
     * Invariant I9 under load: schedulers, failing workers and the reaper all run at once, round after round, until
     * every job is DEAD. Each job ends with exactly its budget of attempts. The CHECK constraint would also turn any
     * attempt beyond the budget into an error, which the test would report.
     */
    @Test
    void retryBudget_holdsWithSchedulersFailuresAndTheReaperRacing() throws Exception {
        var workers = List.of(fixture.worker("w1", 20), fixture.worker("w2", 20), fixture.worker("w3", 20));
        var jobs = submitMany(50, 3);

        for (int round = 0; round < 30 && !allDead(jobs); round++) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> {
                    cycle.runCycle(SchedulingPolicy.LEAST_LOADED);
                    return null;
                });
            }
            for (var worker : workers) {
                tasks.add(() -> {
                    for (var claimed : attempts.claim(worker.id(), 20)) {
                        if (claimed.attemptNo() % 2 == 0) {
                            fixture.expireLease(claimed.attemptId());
                        } else {
                            attempts.report(
                                    worker.id(),
                                    claimed.attemptId(),
                                    AttemptOutcome.FAILED,
                                    FailureClass.TRANSIENT,
                                    "x",
                                    null,
                                    null);
                        }
                    }
                    return null;
                });
            }
            tasks.add(() -> {
                attempts.recoverExpiredLeases(50);
                return null;
            });

            assertThat(runConcurrently(tasks)).as("errors in round %d", round).isEmpty();
            jdbc.sql("UPDATE jobs SET available_at = now() WHERE status = 'RETRY_WAIT'")
                    .update();
            // Leases expired for ASSIGNED attempts too (placed but not claimed this round) are left to later rounds.
        }

        assertThat(allDead(jobs)).as("every job exhausted its budget").isTrue();
        var counts = jdbc.sql("""
                        SELECT j.attempt_count, (SELECT count(*) FROM job_attempts a WHERE a.job_id = j.id)
                        FROM jobs j
                        """)
                .query((rs, row) -> List.of(rs.getLong(1), rs.getLong(2)))
                .list();
        assertThat(counts).hasSize(50).allSatisfy(pair -> assertThat(pair).containsExactly(3L, 3L));
    }

    @Test
    void rateLimitedFailure_waitsAtLeastAsLongAsTheProviderAsked() {
        var worker = fixture.worker("w", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);
        var before = jdbc.sql("SELECT now()").query(Instant.class).single();

        attempts.report(
                worker.id(),
                attempt,
                AttemptOutcome.FAILED,
                FailureClass.RATE_LIMITED,
                "429",
                null,
                Duration.ofSeconds(90));

        var availableAt = jdbc.sql("SELECT available_at FROM jobs WHERE id = :j")
                .param("j", job)
                .query(Instant.class)
                .single();
        // Backoff alone is at most 1 s for a first attempt; the provider's 90 s must win.
        assertThat(Duration.between(before, availableAt)).isGreaterThanOrEqualTo(Duration.ofSeconds(90));
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
    }

    private void raceSchedulersAgainstCancels(List<UUID> jobs) throws Exception {
        var tasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> {
                for (int cycles = 0; cycles < 5; cycles++) {
                    cycle.runCycle(SchedulingPolicy.FIFO);
                }
                return null;
            });
        }
        for (int slice = 0; slice < 4; slice++) {
            var mine = new ArrayList<UUID>();
            for (int i = slice; i < jobs.size(); i += 4) {
                mine.add(jobs.get(i));
            }
            tasks.add(() -> {
                for (var job : mine) {
                    lifecycle.cancel(job);
                }
                return null;
            });
        }
        assertThat(runConcurrently(tasks)).isEmpty();
    }

    private void assertEveryCancelTookEffect(List<UUID> jobs) {
        var violations = jdbc.sql("""
                        SELECT count(*) FROM jobs
                        WHERE id = ANY(:ids)
                          AND NOT (status = 'CANCELLED'
                                   OR (status IN ('SCHEDULED', 'RUNNING') AND cancel_requested_at IS NOT NULL))
                        """)
                .param("ids", jobs.toArray(UUID[]::new))
                .query(Long.class)
                .single();
        assertThat(violations).as("jobs whose cancellation was lost").isZero();
        var activeOnCancelled = jdbc.sql("""
                        SELECT count(*) FROM jobs j JOIN job_attempts a ON a.job_id = j.id
                        WHERE j.status = 'CANCELLED' AND a.status IN ('ASSIGNED', 'RUNNING')
                        """).query(Long.class).single();
        assertThat(activeOnCancelled)
                .as("cancelled jobs still holding an attempt")
                .isZero();
    }

    /** The worker side of a cooperative cancel: claim, learn about it on the heartbeat, report CANCELLED. */
    private void finishCooperativeCancels(List<ExecutionFixture.RegisteredWorker> workers) {
        for (var worker : workers) {
            attempts.claim(worker.id(), 30);
            var running = jdbc.sql("SELECT id FROM job_attempts WHERE worker_id = :w AND status = 'RUNNING'")
                    .param("w", worker.id())
                    .query(UUID.class)
                    .list();
            var toCancel = attempts.renewLeases(worker.id(), running).cancelRequested();
            assertThat(toCancel).containsExactlyInAnyOrderElementsOf(running);
            toCancel.forEach(
                    attempt -> attempts.report(worker.id(), attempt, AttemptOutcome.CANCELLED, null, null, null, null));
        }
    }

    private void assertAllCancelledAndNeverPlacedAgain(List<UUID> jobs) {
        jobs.forEach(job -> assertThat(fixture.jobStatus(job)).isEqualTo("CANCELLED"));
        jdbc.sql("UPDATE jobs SET available_at = now()").update();
        assertThat(fixture.place()).isZero();
        fixture.assertReservationsMatchActiveAttempts();
    }

    private List<UUID> submitMany(int count, int maxAttempts) {
        var jobs = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            jobs.add(fixture.submit(maxAttempts));
        }
        return jobs;
    }

    private boolean allDead(List<UUID> jobs) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE id = ANY(:ids) AND status <> 'DEAD'")
                        .param("ids", jobs.toArray(UUID[]::new))
                        .query(Long.class)
                        .single()
                == 0;
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
