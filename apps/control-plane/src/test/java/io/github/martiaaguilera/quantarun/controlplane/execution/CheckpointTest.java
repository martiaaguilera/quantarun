package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Invariant I14: only the running attempt commits checkpoints, stages commit in order, and a retry resumes. */
@IntegrationTest
class CheckpointTest {

    @Autowired
    MockMvc mvc;

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
    ExecutionFixture.RegisteredWorker worker;
    UUID job;
    UUID attempt;

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        worker = fixture.worker("stager", 2);
        job = submitStaged();
        fixture.place();
        attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 2);
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    @Test
    void stagesCommitInOrder_andARetryResumesAfterTheLastOne() throws Exception {
        checkpoint(worker, attempt, 0, "{\"digest\":\"a\"}").andExpect(status().isOk());
        checkpoint(worker, attempt, 1, "{\"digest\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyCommitted").value(false));

        fixture.expireLease(attempt);
        attempts.recoverExpiredLeases(10);
        fixture.place();
        var resumed = attempts.claim(worker.id(), 2);

        assertThat(resumed).singleElement().satisfies(claimed -> {
            assertThat(claimed.attemptNo()).isEqualTo(2);
            assertThat(claimed.lastCheckpoint()).isNotNull();
            assertThat(claimed.lastCheckpoint().stageIndex()).isEqualTo(1);
            assertThat(claimed.lastCheckpoint().result()).containsEntry("digest", "b");
        });
        var resumeNotes = jdbc.sql("""
                        SELECT details->>'resume' FROM job_events WHERE job_id = :j AND type = 'STARTED' ORDER BY id
                        """).param("j", job).query(String.class).list();
        assertThat(resumeNotes).containsExactly("from zero", "after stage 1");
        mvc.perform(get("/api/v1/jobs/" + job + "/checkpoints")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].stageIndex").value(1))
                .andExpect(jsonPath("$[1].attemptId").value(attempt.toString()))
                .andExpect(jsonPath("$[1].result.digest").value("b"));
    }

    @Test
    void stagesCannotSkip_goBackwards_orBeRewritten() throws Exception {
        checkpoint(worker, attempt, 1, "{\"digest\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_OUT_OF_ORDER"));
        checkpoint(worker, attempt, 0, "{\"digest\":\"a\"}").andExpect(status().isOk());
        // The same stage and result again is a retried delivery, answered idempotently.
        checkpoint(worker, attempt, 0, "{\"digest\":\"a\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyCommitted").value(true));
        checkpoint(worker, attempt, 0, "{\"digest\":\"different\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_OUT_OF_ORDER"));

        assertThat(fixture.eventCount(job, "CHECKPOINT_COMMITTED")).isEqualTo(1);
    }

    @Test
    void staleOrForeignAttempts_cannotCommit() throws Exception {
        var other = fixture.worker("other", 1);
        checkpoint(other, attempt, 0, "{}").andExpect(status().isNotFound());

        fixture.expireLease(attempt);
        attempts.recoverExpiredLeases(10);
        checkpoint(worker, attempt, 0, "{\"digest\":\"late\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ACTIVE"));

        assertThat(count("SELECT count(*) FROM job_checkpoints")).isZero();
    }

    @Test
    void oversizedResults_areRefused() throws Exception {
        var big = "{\"blob\":\"" + "x".repeat(JobAttempts.MAX_CHECKPOINT_BYTES) + "\"}";

        checkpoint(worker, attempt, 0, big)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_TOO_LARGE"));
        checkpoint(worker, attempt, 100, "{}").andExpect(status().isBadRequest());
    }

    /**
     * A checkpoint racing the reaper's recovery of the same attempt, many times: whichever takes the attempt's row lock
     * first wins. A checkpoint is either committed while the attempt still ran (committed before it was finished) or
     * rejected because it no longer ran; never committed by an attempt that was already recovered.
     */
    @Test
    void checkpointRacingLeaseRecovery_isNeverCommittedByARecoveredAttempt() throws Exception {
        var committedWins = 0;
        var recoveredFirst = 0;
        for (int round = 0; round < 30; round++) {
            fixture.reset();
            worker = fixture.worker("racer", 1);
            job = submitStaged();
            fixture.place();
            attempt = fixture.latestAttempt(job);
            attempts.claim(worker.id(), 1);
            fixture.expireLease(attempt);

            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            JobAttempts.CheckpointResult[] result = new JobAttempts.CheckpointResult[1];
            try (var executor = Executors.newFixedThreadPool(2)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    result[0] = attempts.commitCheckpoint(worker.id(), attempt, 0, Map.of("digest", "a"));
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    start.await();
                    attempts.recoverExpiredLeases(10);
                    return null;
                }));
                start.countDown();
                for (var future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            }

            // A reaper that found the attempt locked by the checkpoint skipped it (SKIP LOCKED); the next tick gets it.
            attempts.recoverExpiredLeases(10);
            assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
            var committed = count("SELECT count(*) FROM job_checkpoints");
            if (result[0] instanceof JobAttempts.CheckpointResult.Committed) {
                assertThat(committed).isEqualTo(1);
                assertThat(count("""
                                SELECT count(*) FROM job_checkpoints c JOIN job_attempts a ON a.id = c.attempt_id
                                WHERE c.committed_at <= a.finished_at
                                """))
                        .as("committed while the attempt was still running")
                        .isEqualTo(1);
                committedWins++;
            } else {
                assertThat(result[0]).isInstanceOf(JobAttempts.CheckpointResult.NotActive.class);
                assertThat(committed).isZero();
                recoveredFirst++;
            }
        }
        assertThat(committedWins + recoveredFirst).isEqualTo(30);
    }

    private UUID submitStaged() {
        var payload = json.createObjectNode();
        var stages = payload.putArray("stages");
        stages.addObject().put("durationMs", 10);
        stages.addObject().put("durationMs", 10);
        stages.addObject().put("durationMs", 10);
        return fixture.submit(WorkloadType.STAGED, payload, 3);
    }

    private ResultActions checkpoint(ExecutionFixture.RegisteredWorker who, UUID attemptId, int stage, String result)
            throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/attempts/" + attemptId + "/checkpoints")
                .header(HttpHeaders.AUTHORIZATION, who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stageIndex\":" + stage + ",\"result\":" + result + "}"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
