package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts.ReportResult;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leases, recovery and the races between them, against real PostgreSQL 18 (invariants I2, I6, I9, I10, I11). The
 * service methods are called directly, the same ones the HTTP controller and the background reaper call.
 */
@IntegrationTest
class LeaseRecoveryTest {

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
    void expiredRunningLease_isRecovered_releasesCapacity_andTheJobIsPlacedAgainAtOnce() {
        var lost = fixture.worker("lost", 1);
        var job = fixture.submit(3);
        fixture.place();
        var first = fixture.latestAttempt(job);
        attempts.claim(lost.id(), 1);
        fixture.expireLease(first);
        // The dead worker's slot must not be the only place the job fits; a second worker takes over.
        fixture.retire(lost.id());
        var rescuer = fixture.worker("rescuer", 1);

        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);

        assertThat(fixture.attemptStatus(first)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
        assertThat(fixture.slotsReserved(lost.id())).isZero();
        assertThat(fixture.eventCount(job, "ATTEMPT_LOST")).isEqualTo(1);
        assertThat(fixture.eventCount(job, "RETRY_SCHEDULED")).isEqualTo(1);
        // WORKER_LOST retries without backoff: the work did not fail, its host did.
        assertThat(fixture.place()).isEqualTo(1);
        var second = fixture.latestAttempt(job);
        assertThat(second).isNotEqualTo(first);
        assertThat(attempts.claim(rescuer.id(), 1)).singleElement().satisfies(claimed -> {
            assertThat(claimed.attemptId()).isEqualTo(second);
            assertThat(claimed.attemptNo()).isEqualTo(2);
        });
        var retryDecision = jdbc.sql("SELECT failure_class || ' / ' || retry_decision FROM job_attempts WHERE id = :id")
                .param("id", first)
                .query(String.class)
                .single();
        assertThat(retryDecision).isEqualTo("WORKER_LOST / retry after 0 ms");
    }

    @Test
    void assignedAttemptNeverClaimed_expires_andTheLateClaimGetsNothing() {
        var worker = fixture.worker("sleepy", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        fixture.expireLease(attempt);

        assertThat(attempts.claim(worker.id(), 1)).isEmpty();
        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
        assertThat(attempts.claim(worker.id(), 1)).isEmpty();
    }

    @Test
    void recovery_takesAtMostTheRequestedBatch() {
        var worker = fixture.worker("crowded", 5);
        var jobs = List.of(fixture.submit(3), fixture.submit(3), fixture.submit(3));
        fixture.place();
        jobs.forEach(job -> fixture.expireLease(fixture.latestAttempt(job)));

        assertThat(attempts.recoverExpiredLeases(2)).isEqualTo(2);
        assertThat(attempts.recoverExpiredLeases(2)).isEqualTo(1);
        assertThat(attempts.recoverExpiredLeases(2)).isZero();
        assertThat(fixture.slotsReserved(worker.id())).isZero();
    }

    @Test
    void leaseExpiryWithAPendingCancel_endsCancelled_andIsNeverRetried() {
        var worker = fixture.worker("unresponsive", 1);
        var job = fixture.submit(3);
        fixture.place();
        attempts.claim(worker.id(), 1);
        lifecycle.cancel(job);
        fixture.expireLease(fixture.latestAttempt(job));

        attempts.recoverExpiredLeases(10);

        assertThat(fixture.jobStatus(job)).isEqualTo("CANCELLED");
        assertThat(fixture.place()).isZero();
    }

    @Test
    void cancelFlagged_isListedOnHeartbeat_andTheWorkersCancelledReportEndsTheJob() {
        var worker = fixture.worker("cooperative", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);

        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).cancelRequested())
                .isEmpty();
        lifecycle.cancel(job);
        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).cancelRequested())
                .containsExactly(attempt);

        var result = attempts.report(worker.id(), attempt, AttemptOutcome.CANCELLED, null, null, null, null);

        assertThat(result).isInstanceOf(ReportResult.Applied.class);
        assertThat(fixture.jobStatus(job)).isEqualTo("CANCELLED");
        assertThat(fixture.place()).isZero();
    }

    @Test
    void nonRetryableFailure_endsFailedAfterOneAttempt() {
        var worker = fixture.worker("strict", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);

        attempts.report(
                worker.id(), attempt, AttemptOutcome.FAILED, FailureClass.INVALID_INPUT, "bad payload", null, null);

        assertThat(fixture.jobStatus(job)).isEqualTo("FAILED");
        assertThat(fixture.eventCount(job, "FAILED")).isEqualTo(1);
        fixture.makeRunnableNow(job);
        assertThat(fixture.place()).isZero();
    }

    /**
     * Invariant I9 for every budget from 1 to 4, mixing reported failures with lost workers: the job runs exactly
     * {@code maxAttempts} times, then goes DEAD and is never placed again.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void retryableFailures_retryUpToTheBudget_thenTheJobIsDead(int maxAttempts) {
        var worker = fixture.worker("flaky", 1);
        var job = fixture.submit(maxAttempts);

        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            assertThat(fixture.place()).as("placement of attempt %d", attemptNo).isEqualTo(1);
            var attempt = fixture.latestAttempt(job);
            attempts.claim(worker.id(), 1);
            if (attemptNo % 2 == 0) {
                fixture.expireLease(attempt);
                attempts.recoverExpiredLeases(10);
            } else {
                attempts.report(
                        worker.id(), attempt, AttemptOutcome.FAILED, FailureClass.TRANSIENT, "boom", null, null);
            }
            var expected = attemptNo < maxAttempts ? "RETRY_WAIT" : "DEAD";
            assertThat(fixture.jobStatus(job)).as("after attempt %d", attemptNo).isEqualTo(expected);
            fixture.makeRunnableNow(job);
        }

        assertThat(fixture.place()).isZero();
        var counts = jdbc.sql("""
                        SELECT j.attempt_count, (SELECT count(*) FROM job_attempts a WHERE a.job_id = j.id)
                        FROM jobs j WHERE j.id = :id
                        """)
                .param("id", job)
                .query((rs, row) -> List.of(rs.getLong(1), rs.getLong(2)))
                .single();
        assertThat(counts).containsExactly((long) maxAttempts, (long) maxAttempts);
        assertThat(fixture.eventCount(job, "DEAD")).isEqualTo(1);
    }

    @Test
    void restart_extendsLeasesThatExpiredWhileTheControlPlaneWasDown() {
        var worker = fixture.worker("survivor", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);
        fixture.expireLease(attempt);

        assertThat(attempts.extendActiveLeasesAfterRestart()).isEqualTo(1);

        assertThat(attempts.recoverExpiredLeases(10)).isZero();
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("RUNNING");
        // The worker is alive, so its next heartbeat renews the lease as usual.
        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).renewedRunning())
                .containsExactly(attempt);
    }

    /**
     * Invariants I6 and I10: a worker reporting success while its lease is being reaped. Over many rounds, with
     * several reapers sharing the work, every attempt has exactly one outcome: either the report won (SUCCEEDED, and
     * the reaper skipped it) or the reaper won (LOST, and the report was rejected). The reservation is released exactly
     * once either way; a second release would trip the CHECK constraint and fail the test with an exception.
     */
    @Test
    void completionRacingLeaseExpiry_hasExactlyOneWinnerPerAttempt() throws Exception {
        var rounds = 8;
        var jobsPerRound = 40;
        var totalReportWins = 0;
        var totalReaperWins = 0;
        for (int round = 0; round < rounds; round++) {
            fixture.reset();
            var workers = List.of(
                    fixture.worker("w1", 10),
                    fixture.worker("w2", 10),
                    fixture.worker("w3", 10),
                    fixture.worker("w4", 10));
            var jobs = new ArrayList<UUID>();
            for (int i = 0; i < jobsPerRound; i++) {
                jobs.add(fixture.submit(3));
            }
            while (fixture.place() > 0) {}
            var owners = new ConcurrentHashMap<UUID, UUID>();
            for (var worker : workers) {
                attempts.claim(worker.id(), 10).forEach(claimed -> owners.put(claimed.attemptId(), worker.id()));
            }
            assertThat(owners).hasSize(jobsPerRound);
            owners.keySet().forEach(fixture::expireLease);

            var reported = new ConcurrentHashMap<UUID, ReportResult>();
            var reaped = new AtomicInteger();
            var tasks = new ArrayList<Callable<Void>>();
            owners.forEach((attempt, worker) -> tasks.add(() -> {
                reported.put(
                        attempt, attempts.report(worker, attempt, AttemptOutcome.SUCCEEDED, null, null, null, null));
                return null;
            }));
            for (int reaper = 0; reaper < 4; reaper++) {
                tasks.add(() -> {
                    int count;
                    do {
                        count = attempts.recoverExpiredLeases(7);
                        reaped.addAndGet(count);
                    } while (count > 0);
                    return null;
                });
            }

            assertThat(runConcurrently(tasks)).as("errors in round %d", round).isEmpty();

            var reportWins = 0;
            for (var attempt : owners.keySet()) {
                var status = fixture.attemptStatus(attempt);
                var result = reported.get(attempt);
                if (status.equals("SUCCEEDED")) {
                    assertThat(result).isInstanceOf(ReportResult.Applied.class);
                    reportWins++;
                } else {
                    assertThat(status).isEqualTo("LOST");
                    assertThat(result).isInstanceOf(ReportResult.Rejected.class);
                }
            }
            assertThat(reaped.get()).isEqualTo(jobsPerRound - reportWins);
            for (var job : jobs) {
                var successes = fixture.eventCount(job, "SUCCEEDED");
                var losses = fixture.eventCount(job, "ATTEMPT_LOST");
                assertThat(successes + losses)
                        .as("outcomes recorded for job %s", job)
                        .isEqualTo(1);
                assertThat(fixture.jobStatus(job)).isEqualTo(successes == 1 ? "SUCCEEDED" : "RETRY_WAIT");
            }
            for (var worker : workers) {
                assertThat(fixture.slotsReserved(worker.id())).isZero();
            }
            fixture.assertReservationsMatchActiveAttempts();
            totalReportWins += reportWins;
            totalReaperWins += jobsPerRound - reportWins;
        }
        // Not an invariant, but a sanity check that the test really raced: both sides must have won sometimes.
        assertThat(totalReportWins).as("report wins").isPositive();
        assertThat(totalReaperWins).as("reaper wins").isPositive();
    }

    /**
     * Invariant I10: heartbeats racing the reaper never revive an expired lease, and each expired attempt is recovered
     * exactly once, while the live attempts of the same worker keep being renewed.
     */
    @Test
    void heartbeatRacingTheReaper_neverRevivesAnExpiredLease() throws Exception {
        for (int round = 0; round < 5; round++) {
            fixture.reset();
            var worker = fixture.worker("contested", 20);
            var jobs = new ArrayList<UUID>();
            for (int i = 0; i < 20; i++) {
                jobs.add(fixture.submit(3));
            }
            fixture.place();
            var running = attempts.claim(worker.id(), 20).stream()
                    .map(JobAttempts.Claimed::attemptId)
                    .toList();
            assertThat(running).hasSize(20);
            Set<UUID> expired = new HashSet<>(running.subList(0, 10));
            expired.forEach(fixture::expireLease);

            var renewedSeen = ConcurrentHashMap.<UUID>newKeySet();
            var lostSeen = ConcurrentHashMap.<UUID>newKeySet();
            var tasks = new ArrayList<Callable<Void>>();
            for (int beat = 0; beat < 8; beat++) {
                tasks.add(() -> {
                    for (int i = 0; i < 5; i++) {
                        var renewal = attempts.renewLeases(worker.id(), running);
                        renewedSeen.addAll(renewal.renewedRunning());
                        lostSeen.addAll(renewal.lost());
                    }
                    return null;
                });
            }
            for (int reaper = 0; reaper < 2; reaper++) {
                tasks.add(() -> {
                    attempts.recoverExpiredLeases(50);
                    return null;
                });
            }

            assertThat(runConcurrently(tasks)).isEmpty();

            assertThat(renewedSeen).doesNotContainAnyElementsOf(expired);
            assertThat(lostSeen).containsExactlyInAnyOrderElementsOf(expired);
            for (var attempt : running) {
                assertThat(fixture.attemptStatus(attempt)).isEqualTo(expired.contains(attempt) ? "LOST" : "RUNNING");
            }
            for (var job : jobs) {
                assertThat(fixture.eventCount(job, "ATTEMPT_LOST")).isLessThanOrEqualTo(1);
            }
            var lostEvents = jdbc.sql("SELECT count(*) FROM job_events WHERE type = 'ATTEMPT_LOST'")
                    .query(Long.class)
                    .single();
            assertThat(lostEvents).isEqualTo(10);
            assertThat(fixture.slotsReserved(worker.id())).isEqualTo(10);
            fixture.assertReservationsMatchActiveAttempts();
        }
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
