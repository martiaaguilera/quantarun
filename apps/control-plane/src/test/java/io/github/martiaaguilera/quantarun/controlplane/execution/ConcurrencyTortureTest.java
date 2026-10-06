package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts.CheckpointResult;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts.Claimed;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts.ReportResult;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 12: every actor that changes job state runs at once against real PostgreSQL, and the database is audited for
 * the invariants while they run and after the system drains. The single-race tests elsewhere prove one interleaving
 * family each; this one looks for the interactions between them: schedulers under every policy, workers that claim,
 * heartbeat, checkpoint, succeed, fail, report twice, report after losing their lease and crash, a reaper, lease
 * expiry, cancellation, revive and duplicate submissions.
 *
 * <p>The chaos phase lasts {@code quantarun.torture.seconds} (default 8), so CI runs a short pass and
 * {@code scripts/repeat-race-tests.sh} runs long ones.
 */
@IntegrationTest
class ConcurrencyTortureTest {

    private static final int WORKERS = 6;
    private static final int SCHEDULERS = 4;
    private static final Duration CHAOS = Duration.ofSeconds(Long.getLong("quantarun.torture.seconds", 8));
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(90);
    /** Bounds the backlog, so the drain measures recovery rather than raw throughput. */
    private static final long MAX_SUBMISSIONS = 1_500;

    private static final List<String> UNFINISHED = List.of("QUEUED", "RETRY_WAIT", "SCHEDULED", "RUNNING");

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

    private final ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
    private final Map<String, LongAdder> stats = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> jobsByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicBoolean chaos = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong audits = new AtomicLong();
    private List<UUID> projects = List.of();

    @Test
    void everyActorAtOnce_neverBreaksAnInvariant_andEveryJobFinishes() throws Exception {
        new ExecutionFixture(jdbc, registry, lifecycle, cycle, json).reset();
        jdbc.sql("TRUNCATE job_checkpoints").update();
        projects = List.of(project("torture-a"), project("torture-b"));
        for (int i = 0; i < 120; i++) {
            submit(null);
        }
        // Workers and schedulers start with a backlog, and submissions keep arriving while everything else runs.

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < SCHEDULERS; i++) {
            loop(pool, "scheduler", this::schedule);
        }
        for (int i = 0; i < WORKERS; i++) {
            var worker = new SimulatedWorker(i);
            loop(pool, "worker", worker::step);
        }
        loop(pool, "reaper", () -> count("recovered", attempts.recoverExpiredLeases(25)));
        loop(pool, "clock", this::advanceTime);
        loop(pool, "submitter", () -> submit(null));
        loop(pool, "duplicate submitter", () -> submit(sharedKey()));
        loop(pool, "duplicate submitter", () -> submit(sharedKey()));
        loop(pool, "canceller", this::cancelSomething);
        loop(pool, "reviver", this::reviveSomething);
        loop(pool, "auditor", this::auditSnapshot);

        Thread.sleep(CHAOS);
        chaos.set(false);
        var drainedIn = drain();
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        System.out.printf(
                "Torture: %s chaos, drained in %s, %d snapshot audits, %s%n",
                CHAOS, drainedIn, audits.get(), new TreeMap<>(stats));
        assertThat(unexpected).as("unexpected exceptions from the actors").isEmpty();
        assertThat(violations)
                .as("invariant violations seen while the actors ran")
                .isEmpty();
        assertThat(drainedIn)
                .as("every job reached a final state once chaos stopped; still unfinished: %s", unfinishedDetail())
                .isNotNull();
        auditFinalState();
        assertThat(stats.get("placed").sum()).isPositive();
        assertThat(stats.get("lost").sum())
                .as("leases expired under load, so recovery was exercised")
                .isPositive();
    }

    // --- actors ---------------------------------------------------------------------------------------------------

    private void schedule() {
        var policies = SchedulingPolicy.values();
        var policy = policies[ThreadLocalRandom.current().nextInt(policies.length)];
        count("placed", cycle.runCycle(policy).placed());
    }

    /** Lease expiry and retry backoff happen in time; moving the clock in SQL makes them happen every few ms. */
    private void advanceTime() throws InterruptedException {
        // Every statement here skips rows that are locked instead of waiting for them. The clock is test scaffolding,
        // not a participant: an UPDATE over several rows locks them in whatever order its plan produces, and waiting
        // in that order deadlocked against the product's id-ordered locks in CI (2026-10-06). A skipped row simply
        // ages on a later tick.
        if (chaos.get() && ThreadLocalRandom.current().nextInt(3) == 0) {
            count("leasesExpired", jdbc.sql("""
                                    UPDATE job_attempts SET lease_expires_at = now() - interval '1 second'
                                    WHERE id IN (SELECT id FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING')
                                                 ORDER BY random() LIMIT 2 FOR UPDATE SKIP LOCKED)
                                    """).update());
        }
        // The attempts of crashed registrations would otherwise wait out a real lease.
        jdbc.sql("""
                        UPDATE job_attempts SET lease_expires_at = now() - interval '1 second'
                        WHERE id IN (SELECT a.id FROM job_attempts a JOIN workers w ON w.id = a.worker_id
                                     WHERE w.lifecycle = 'OFFLINE' AND a.status IN ('ASSIGNED', 'RUNNING')
                                       AND a.lease_expires_at > now()
                                     FOR UPDATE OF a SKIP LOCKED)
                        """).update();
        jdbc.sql("""
                        UPDATE jobs SET available_at = now()
                        WHERE id IN (SELECT id FROM jobs WHERE status = 'RETRY_WAIT' AND available_at > now()
                                     FOR UPDATE SKIP LOCKED)
                        """).update();
        Thread.sleep(5);
    }

    private void submit(String idempotencyKey) throws InterruptedException {
        if (!chaos.get() || stats.getOrDefault("submitted", new LongAdder()).sum() >= MAX_SUBMISSIONS) {
            Thread.sleep(10);
            return;
        }
        var random = ThreadLocalRandom.current();
        var accelerators = random.nextInt(10) == 0 ? 1 : 0;
        var submission = new JobSubmission(
                WorkloadType.DELAY,
                json.createObjectNode().put("durationMs", 10),
                random.nextInt(1, 10),
                new ResourceRequest(random.nextBoolean() ? 500 : 1000, random.nextBoolean() ? 256 : 1024, accelerators),
                List.of(),
                random.nextInt(1, 4),
                60,
                null,
                null);
        // A shared key is only ever used with this one body, so every duplicate must resolve to the same job.
        if (idempotencyKey != null) {
            submission = new JobSubmission(
                    WorkloadType.DELAY,
                    json.createObjectNode().put("durationMs", 10),
                    5,
                    new ResourceRequest(500, 256, 0),
                    List.of(),
                    2,
                    60,
                    null,
                    null);
        }
        try {
            var project = idempotencyKey != null ? projects.getFirst() : projects.get(random.nextInt(projects.size()));
            var job = lifecycle.submit(project, submission, idempotencyKey).job();
            count("submitted", 1);
            if (idempotencyKey != null) {
                jobsByIdempotencyKey
                        .computeIfAbsent(idempotencyKey, k -> ConcurrentHashMap.newKeySet())
                        .add(job.id());
            }
        } catch (ApiException e) {
            expectStatus(e, 429, "submitRejectedByQuota");
        }
    }

    private String sharedKey() {
        return "dup-" + ThreadLocalRandom.current().nextInt(40);
    }

    private void cancelSomething() throws InterruptedException {
        if (chaos.get()) {
            randomJob("status <> 'CANCELLED'").ifPresent(job -> {
                try {
                    count("cancel." + lifecycle.cancel(job), 1);
                } catch (ApiException e) {
                    expectStatus(e, 409, "cancelRefused");
                }
            });
        }
        Thread.sleep(20);
    }

    private void reviveSomething() throws InterruptedException {
        if (chaos.get()) {
            randomJob("status = 'DEAD'").ifPresent(job -> {
                try {
                    lifecycle.revive(job);
                    count("revived", 1);
                } catch (ApiException e) {
                    expectStatus(e, 409, "reviveRefused");
                }
            });
        }
        Thread.sleep(15);
    }

    /**
     * One statement sees one snapshot, and reservations change in the same transaction as the attempts that hold them,
     * so these must hold at every instant, not only after the system settles.
     */
    private void auditSnapshot() {
        var overbooked = jdbc.sql("""
                        SELECT count(*) FROM workers
                        WHERE cpu_millis_reserved > cpu_millis_capacity OR memory_mib_reserved > memory_mib_capacity
                           OR accelerators_reserved > accelerator_capacity OR slots_reserved > slot_capacity
                           OR least(cpu_millis_reserved, memory_mib_reserved, accelerators_reserved, slots_reserved) < 0
                        """).query(Long.class).single();
        record("I1: a worker over capacity or below zero", overbooked);
        record("I2: reservations disagree with active attempts", reservationMismatches());
        record(
                "I3: a job with two active attempts",
                jdbc.sql("""
                        SELECT count(*) FROM (SELECT job_id FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING')
                                              GROUP BY job_id HAVING count(*) > 1) x
                        """).query(Long.class).single());
        audits.incrementAndGet();
    }

    /** A worker process: claims for its free slots, heartbeats, and does one random thing with each running attempt. */
    private final class SimulatedWorker {

        private final int index;
        private UUID id;
        private final Map<UUID, Claimed> running = new HashMap<>();
        private final Map<UUID, Integer> nextStage = new HashMap<>();

        SimulatedWorker(int index) {
            this.index = index;
            register();
        }

        private void register() {
            var accelerators = index % 3 == 0 ? 2 : 0;
            id = registry.register(new WorkerProtocol.RegisterRequest(
                            "torture-" + index,
                            "test",
                            new WorkerProtocol.Capacity(3000, 4096, accelerators, 3),
                            List.of()))
                    .workerId();
            running.clear();
            nextStage.clear();
        }

        void step() throws InterruptedException {
            var random = ThreadLocalRandom.current();
            if (chaos.get() && random.nextInt(200) == 0) {
                crash();
                return;
            }
            for (var claimed : attempts.claim(id, 3 - running.size())) {
                running.put(claimed.attemptId(), claimed);
                var last = claimed.lastCheckpoint();
                nextStage.put(claimed.attemptId(), last == null ? 0 : last.stageIndex() + 1);
                count("claimed", 1);
            }
            // The heartbeat endpoint does both: liveness for the scheduler, lease renewal for the attempts.
            registry.heartbeat(id);
            var renewal = attempts.renewLeases(id, List.copyOf(running.keySet()));
            for (var lost : renewal.lost()) {
                running.remove(lost);
                count("lost", 1);
                // A worker that missed its lease reports anyway: the report must be fenced out (I6, I10).
                var late = attempts.report(id, lost, AttemptOutcome.SUCCEEDED, null, null, null, null);
                if (late instanceof ReportResult.Applied) {
                    violations.add("I6: a report for a lost attempt was applied: " + lost);
                }
            }
            for (var cancelled : renewal.cancelRequested()) {
                if (running.remove(cancelled) != null) {
                    attempts.report(id, cancelled, AttemptOutcome.CANCELLED, null, null, null, null);
                    count("reportedCancelled", 1);
                }
            }
            for (var attemptId : List.copyOf(running.keySet())) {
                act(attemptId, random.nextInt(100));
            }
            Thread.sleep(random.nextInt(1, 6));
        }

        private void act(UUID attemptId, int roll) {
            if (!chaos.get() || roll < 35) {
                finish(attemptId, AttemptOutcome.SUCCEEDED, null);
            } else if (roll < 50) {
                finish(
                        attemptId,
                        AttemptOutcome.FAILED,
                        roll < 47 ? FailureClass.TRANSIENT : FailureClass.NON_RETRYABLE);
            } else if (roll < 65) {
                int stage = nextStage.get(attemptId);
                var result = attempts.commitCheckpoint(id, attemptId, stage, Map.of("stage", stage));
                if (result instanceof CheckpointResult.Committed) {
                    nextStage.put(attemptId, stage + 1);
                    count("checkpoints", 1);
                }
            }
            // Otherwise the attempt keeps running until a later step.
        }

        private void finish(UUID attemptId, AttemptOutcome outcome, FailureClass failure) {
            running.remove(attemptId);
            var first = attempts.report(id, attemptId, outcome, failure, "torture", null, null);
            count("report." + first.getClass().getSimpleName(), 1);
            if (ThreadLocalRandom.current().nextInt(5) == 0) {
                // At-least-once delivery: the retried report must never change anything (I6).
                var again = attempts.report(id, attemptId, outcome, failure, "torture", null, null);
                if (again instanceof ReportResult.Applied) {
                    violations.add("I6: a duplicate report was applied twice: " + attemptId);
                }
                count("duplicateReports", 1);
            }
        }

        /** kill -9: the process forgets everything; the liveness monitor retires the registration; a new one starts. */
        private void crash() {
            jdbc.sql("UPDATE workers SET lifecycle = 'OFFLINE' WHERE id = :id")
                    .param("id", id)
                    .update();
            count("crashes", 1);
            register();
        }
    }

    // --- drain and final audit -------------------------------------------------------------------------------------

    private Duration drain() throws InterruptedException {
        long started = System.nanoTime();
        while (System.nanoTime() - started < DRAIN_LIMIT.toNanos()) {
            if (unfinishedJobs() == 0) {
                return Duration.ofNanos(System.nanoTime() - started);
            }
            Thread.sleep(100);
        }
        return null;
    }

    private void auditFinalState() {
        assertThat(unfinishedJobs()).isZero();
        assertThat(reservationMismatches()).as("I2 after drain").isZero();
        assertThat(jdbc.sql("SELECT coalesce(sum(slots_reserved), 0) FROM workers")
                        .query(Long.class)
                        .single())
                .as("nothing is reserved once every job is final")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM jobs j
                        WHERE (SELECT count(*) FROM job_attempts a WHERE a.job_id = j.id AND a.status = 'SUCCEEDED')
                              <> CASE WHEN j.status = 'SUCCEEDED' THEN 1 ELSE 0 END
                        """))
                .as("I6: a succeeded job has exactly one successful attempt, any other job none")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM jobs j
                        WHERE (SELECT count(*) FROM job_attempts a WHERE a.job_id = j.id) <> j.attempt_count
                           OR j.attempt_count - j.budget_start > j.max_attempts
                        """))
                .as("I9: attempts are numbered without gaps and never exceed the current budget")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM (
                            SELECT attempt_id FROM job_events WHERE type = 'ATTEMPT_LOST'
                            GROUP BY attempt_id HAVING count(*) > 1) x
                        """))
                .as("I10: an expired attempt is recovered exactly once")
                .isZero();
        // Event ids are taken while the job row is locked, so per job they are in commit order.
        assertThat(single("""
                        SELECT count(*) FROM job_events s
                        WHERE s.type = 'SCHEDULED' AND EXISTS (
                            SELECT 1 FROM job_events c
                            WHERE c.job_id = s.job_id AND c.type IN ('CANCELLED', 'CANCEL_REQUESTED') AND c.id < s.id)
                        """))
                .as("I12: a job is never placed after its cancellation")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM job_events s
                        WHERE s.type = 'SCHEDULED' AND EXISTS (
                            SELECT 1 FROM job_events d
                            WHERE d.job_id = s.job_id AND d.type = 'DEAD' AND d.id < s.id
                              AND NOT EXISTS (SELECT 1 FROM job_events r WHERE r.job_id = s.job_id
                                              AND r.type = 'REVIVED' AND r.id BETWEEN d.id AND s.id))
                        """))
                .as("I13: a DEAD job is placed again only after a revive")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM job_events e
                        WHERE e.type IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND EXISTS (
                            SELECT 1 FROM job_events later WHERE later.job_id = e.job_id AND later.id > e.id
                              AND later.type NOT IN ('CANCEL_REQUESTED'))
                        """))
                .as("I5: nothing happens to a job after it succeeded, failed or was cancelled")
                .isZero();
        assertThat(single("""
                        SELECT count(*) FROM (
                            SELECT job_id, stage_index, lag(stage_index) OVER (PARTITION BY job_id ORDER BY stage_index) prev
                            FROM job_checkpoints) c
                        WHERE stage_index <> coalesce(prev + 1, 0)
                        """))
                .as("I14: checkpoint stages are contiguous from zero")
                .isZero();
        jobsByIdempotencyKey.forEach((key, ids) -> assertThat(ids)
                .as("I7: every duplicate of %s resolved to one job", key)
                .hasSize(1));
        assertThat(single("""
                        SELECT count(*) FROM (SELECT project_id, idempotency_key FROM jobs
                                              WHERE idempotency_key IS NOT NULL
                                              GROUP BY 1, 2 HAVING count(*) > 1) x
                        """)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private void loop(ExecutorService pool, String name, Step step) {
        pool.submit(() -> {
            while (running.get()) {
                try {
                    step.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    unexpected.add(new AssertionError(name + " failed: " + e, e));
                    return;
                }
            }
        });
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void expectStatus(ApiException e, int status, String counter) {
        if (e.getStatusCode().value() != status) {
            throw e;
        }
        count(counter, 1);
    }

    private void record(String invariant, long offending) {
        if (offending != 0) {
            violations.add(invariant + " (" + offending + " rows)");
        }
    }

    private void count(String name, long delta) {
        stats.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    private java.util.Optional<UUID> randomJob(String condition) {
        return jdbc.sql("SELECT id FROM jobs WHERE " + condition + " ORDER BY random() LIMIT 1")
                .query(UUID.class)
                .optional();
    }

    private long unfinishedJobs() {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE status IN (:s)")
                .param("s", UNFINISHED)
                .query(Long.class)
                .single();
    }

    private List<Map<String, Object>> unfinishedDetail() {
        return jdbc.sql("""
                        SELECT j.status, j.scheduling_outcome, j.scheduling_reason, j.cancel_requested_at IS NOT NULL cancel,
                               a.status attempt, w.name worker, w.lifecycle, a.lease_expires_at > now() lease_held
                        FROM jobs j
                        LEFT JOIN job_attempts a ON a.job_id = j.id AND a.status IN ('ASSIGNED', 'RUNNING')
                        LEFT JOIN workers w ON w.id = a.worker_id
                        WHERE j.status IN (:s) LIMIT 10
                        """).param("s", UNFINISHED).query().listOfRows();
    }

    private long reservationMismatches() {
        return single("""
                SELECT count(*) FROM workers w
                LEFT JOIN (
                    SELECT worker_id, sum(cpu_millis) cpu, sum(memory_mib) mem, sum(accelerators) acc, count(*) slots
                    FROM job_attempts WHERE status IN ('ASSIGNED', 'RUNNING') GROUP BY worker_id
                ) a ON a.worker_id = w.id
                WHERE w.cpu_millis_reserved <> coalesce(a.cpu, 0) OR w.memory_mib_reserved <> coalesce(a.mem, 0)
                   OR w.accelerators_reserved <> coalesce(a.acc, 0) OR w.slots_reserved <> coalesce(a.slots, 0)
                """);
    }

    private long single(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private UUID project(String name) {
        return jdbc.sql("INSERT INTO projects (name) VALUES (:n) RETURNING id")
                .param("n", name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class)
                .single();
    }
}
