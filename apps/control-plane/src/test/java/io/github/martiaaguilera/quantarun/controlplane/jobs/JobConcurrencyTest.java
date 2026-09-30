package io.github.martiaaguilera.quantarun.controlplane.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobRepository;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Races against real PostgreSQL. Every thread waits on one latch so the requests hit the database together
 * rather than in the staggered order thread start-up would otherwise produce.
 */
@IntegrationTest
class JobConcurrencyTest {

    private static final int CONCURRENT_SUBMISSIONS = 500;
    private static final int THREADS = 64;

    @Autowired
    JobLifecycle lifecycle;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    /** Invariant I7: exactly one logical job and one SUBMITTED event, however many duplicates race. */
    @Test
    void concurrentDuplicateSubmissions_createExactlyOneJob() throws Exception {
        var projectId = newProject();
        var submission = delaySubmission(100);
        var createdCount = new AtomicInteger();
        var jobIds = ConcurrentHashMap.<UUID>newKeySet();

        runConcurrently(CONCURRENT_SUBMISSIONS, () -> {
            var result = lifecycle.submit(projectId, submission, "race-key");
            if (result instanceof JobLifecycle.SubmissionResult.Created) {
                createdCount.incrementAndGet();
            }
            jobIds.add(result.job().id());
            return null;
        });

        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(jobIds).hasSize(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE project_id = :p", projectId))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM job_events e JOIN jobs j ON j.id = e.job_id WHERE j.project_id = :p",
                        projectId))
                .isEqualTo(1);
    }

    /** Invariant I8 under concurrency: with two competing bodies, one wins and every other body gets a conflict. */
    @Test
    void concurrentSubmissionsWithDifferentBodies_neverMergeIntoTheWinner() throws Exception {
        var projectId = newProject();
        var bodyA = delaySubmission(100);
        var bodyB = delaySubmission(200);
        var outcomes = new ConcurrentHashMap<String, AtomicInteger>();

        runConcurrently(200, new Callable<>() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Void call() {
                var submission = sequence.incrementAndGet() % 2 == 0 ? bodyA : bodyB;
                try {
                    var result = lifecycle.submit(projectId, submission, "contested-key");
                    var winnerIsThisBody = result.job().payload().equals(submission.payload());
                    outcomes.computeIfAbsent(winnerIsThisBody ? "same-body" : "WRONG-BODY", k -> new AtomicInteger())
                            .incrementAndGet();
                } catch (ApiException e) {
                    outcomes.computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
                }
                return null;
            }
        });

        assertThat(outcomes).doesNotContainKey("WRONG-BODY");
        assertThat(outcomes.get("same-body").get()).isEqualTo(100);
        assertThat(outcomes.get("IDEMPOTENCY_KEY_REUSED").get()).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM jobs WHERE project_id = :p", projectId))
                .isEqualTo(1);
    }

    /**
     * Invariant I12: cancellation racing placement. The stand-in scheduler uses the same conditional transition the
     * real one will; whatever the interleaving, the job ends either CANCELLED (never placed) or SCHEDULED with a
     * pending cancel request, never SCHEDULED with the cancel silently lost.
     */
    @RepeatedTest(20)
    void cancelRacingPlacement_neverLosesTheCancellation() throws Exception {
        var projectId = newProject();
        var job =
                ((JobLifecycle.SubmissionResult.Created) lifecycle.submit(projectId, delaySubmission(10), null)).job();
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var placement = executor.submit(() -> {
                start.await();
                return jobs.transition(job.id(), List.of(JobStatus.QUEUED, JobStatus.RETRY_WAIT), JobStatus.SCHEDULED)
                        .isPresent();
            });
            var cancellation = executor.submit(() -> {
                start.await();
                return lifecycle.cancel(job.id());
            });
            start.countDown();

            var placed = placement.get(10, TimeUnit.SECONDS);
            var outcome = cancellation.get(10, TimeUnit.SECONDS);
            var finalState = jobs.findById(job.id()).orElseThrow();

            if (placed) {
                assertThat(outcome).isEqualTo(JobLifecycle.CancelOutcome.CANCEL_REQUESTED);
                assertThat(finalState.status()).isEqualTo(JobStatus.SCHEDULED);
                assertThat(finalState.cancelRequestedAt()).isNotNull();
            } else {
                assertThat(outcome).isEqualTo(JobLifecycle.CancelOutcome.CANCELLED);
                assertThat(finalState.status()).isEqualTo(JobStatus.CANCELLED);
            }
        }
    }

    private void runConcurrently(int tasks, Callable<Void> task) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private JobSubmission delaySubmission(int durationMs) {
        ObjectNode payload = json.createObjectNode().put("durationMs", durationMs);
        return new JobSubmission(
                WorkloadType.DELAY, payload, 4, new ResourceRequest(100, 64, 0), List.of(), 3, 60, null, null);
    }

    private UUID newProject() {
        return jdbc.sql("INSERT INTO projects (name) VALUES (:name) RETURNING id")
                .param("name", "race-" + UUID.randomUUID().toString().substring(0, 13))
                .query(UUID.class)
                .single();
    }

    private long count(String sql, UUID projectId) {
        return jdbc.sql(sql).param("p", projectId).query(Long.class).single();
    }
}
