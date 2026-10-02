package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Invariants I9 and I13: a DEAD job runs again only after an explicit revive, which grants exactly one fresh budget. */
@IntegrationTest
class ReviveTest {

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

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        worker = fixture.worker("reviver", 4);
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    @Test
    void deadJob_isNeverPlacedAgain_untilRevived_thenGetsExactlyOneFreshBudget() {
        var job = fixture.submit(2);
        failAttempts(job, 2);
        assertThat(fixture.jobStatus(job)).isEqualTo("DEAD");
        fixture.makeRunnableNow(job);
        assertThat(fixture.place()).as("DEAD is not runnable").isZero();

        var revived = lifecycle.revive(job);

        assertThat(revived.status().name()).isEqualTo("QUEUED");
        assertThat(revived.attemptsInBudget()).isZero();
        assertThat(revived.attemptCount()).isEqualTo(2);
        assertThat(fixture.eventCount(job, "REVIVED")).isEqualTo(1);
        // The new budget is the job's max_attempts again; attempt numbers continue from the history.
        failAttempts(job, 2);
        assertThat(fixture.jobStatus(job)).isEqualTo("DEAD");
        var numbers = jdbc.sql("SELECT attempt_no FROM job_attempts WHERE job_id = :j ORDER BY attempt_no")
                .param("j", job)
                .query(Integer.class)
                .list();
        assertThat(numbers).containsExactly(1, 2, 3, 4);
    }

    @Test
    void revive_ofAJobThatIsNotDead_isRefused() {
        var job = fixture.submit(3);

        assertThatThrownBy(() -> lifecycle.revive(job))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("JOB_NOT_DEAD"));
        assertThat(fixture.jobStatus(job)).isEqualTo("QUEUED");
    }

    @Test
    void revive_isCapped() {
        var job = fixture.submit(1);
        for (int i = 0; i < JobLifecycle.MAX_REVIVES; i++) {
            failAttempts(job, 1);
            lifecycle.revive(job);
        }
        failAttempts(job, 1);

        assertThatThrownBy(() -> lifecycle.revive(job))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("REVIVE_LIMIT_REACHED"));
        assertThat(fixture.jobStatus(job)).isEqualTo("DEAD");
    }

    @Test
    void concurrentRevives_reviveExactlyOnce() throws Exception {
        var job = fixture.submit(1);
        failAttempts(job, 1);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        lifecycle.revive(job);
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo("JOB_NOT_DEAD");
                        return false;
                    }
                }));
            }
            start.countDown();
            var winners = 0;
            for (var future : futures) {
                winners += future.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(fixture.eventCount(job, "REVIVED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT revive_count FROM jobs WHERE id = :j")
                        .param("j", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsAttemptsBeyondTheBudget_evenIfTheApplicationTried() {
        var job = fixture.submit(2);

        assertThatThrownBy(() -> jdbc.sql("UPDATE jobs SET attempt_count = 3 WHERE id = :j")
                        .param("j", job)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reviveEndpoint_isScopedToTheJobsProject() throws Exception {
        var support = new ApiTestSupport(mvc, json);
        var owner = support.createProject();
        var stranger = support.createProject();
        var job = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/jobs")
                                .header(HttpHeaders.AUTHORIZATION, owner.bearer())
                                .contentType("application/json")
                                .content("""
                                        {"workloadType":"fail","payload":{"failureClass":"TRANSIENT"},
                                         "maxAttempts":1,"resources":{"cpuMillis":100,"memoryMib":64}}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
        failAttempts(job, 1);

        mvc.perform(post("/api/v1/jobs/" + job + "/revive").header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/" + job + "/revive").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptsInBudget").value(0))
                .andExpect(jsonPath("$.reviveCount").value(1));
        mvc.perform(post("/api/v1/jobs/" + job + "/revive").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_NOT_DEAD"));
    }

    /** Places, claims and fails the job {@code times} times through the real scheduler and report path. */
    private void failAttempts(UUID job, int times) {
        for (int i = 0; i < times; i++) {
            fixture.makeRunnableNow(job);
            assertThat(fixture.place()).isEqualTo(1);
            var attempt = fixture.latestAttempt(job);
            attempts.claim(worker.id(), 4);
            attempts.report(worker.id(), attempt, AttemptOutcome.FAILED, FailureClass.TRANSIENT, "boom", null, null);
        }
    }
}
