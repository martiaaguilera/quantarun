package io.github.martiaaguilera.quantarun.controlplane.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.execution.ExecutionFixture;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Chaos experiments end to end on real PostgreSQL: creation, heartbeat delivery, expiry, and the recovery timeline. */
@IntegrationTest
class ChaosApiTest {

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

    @Autowired
    ChaosExperiments experiments;

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
    void catalog_listsEveryFault_andOnlyOperatorsMayUseChaos() throws Exception {
        var member = new ApiTestSupport(mvc, json).createProject();
        mvc.perform(get("/api/v1/chaos/faults").header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.faults.length()").value(WorkerProtocol.ChaosFault.values().length));
        var worker = fixture.worker("target", 2);

        create(member.bearer(), "{\"fault\":\"KILL_WORKER\",\"workerId\":\"" + worker.id() + "\"}")
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/chaos/experiments").header(HttpHeaders.AUTHORIZATION, member.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aFaultIsDeliveredInTheNextHeartbeat_exactlyOnce() throws Exception {
        var worker = fixture.worker("target", 2);
        var created = body(createAsAdmin("""
                        {"fault":"PROVIDER_RATE_LIMITED","workerId":"%s","count":2,"retryAfterMs":1500}
                        """.formatted(worker.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.parameters.count").value(2)));

        heartbeat(worker)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaos.length()").value(1))
                .andExpect(jsonPath("$.chaos[0].experimentId")
                        .value(created.get("id").asString()))
                .andExpect(jsonPath("$.chaos[0].fault").value("PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.chaos[0].count").value(2))
                .andExpect(jsonPath("$.chaos[0].retryAfterMillis").value(1500));
        heartbeat(worker)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaos.length()").value(0));

        experiment(created.get("id").asString())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredAt").isString());
    }

    @Test
    void requestsAreValidated() throws Exception {
        var worker = fixture.worker("target", 2);
        var job = fixture.submit(3);

        createAsAdmin("{\"fault\":\"KILL_WORKER\"}").andExpect(status().isBadRequest());
        createAsAdmin("{\"fault\":\"KILL_WORKER\",\"workerId\":\"%s\",\"jobId\":\"%s\"}".formatted(worker.id(), job))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CHAOS_TARGET"));
        createAsAdmin("{\"fault\":\"KILL_WORKER\",\"workerId\":\"%s\",\"durationMs\":5000}".formatted(worker.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FAULT_PARAMETERS"));
        createAsAdmin("{\"fault\":\"STALL_ATTEMPTS\",\"workerId\":\"%s\",\"count\":500}".formatted(worker.id()))
                .andExpect(status().isBadRequest());
        createAsAdmin("{\"fault\":\"SHELL\",\"workerId\":\"%s\"}".formatted(worker.id()))
                .andExpect(status().isBadRequest());
        createAsAdmin("{\"fault\":\"KILL_WORKER\",\"workerId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // The job is queued, not on a worker yet.
        createAsAdmin("{\"fault\":\"KILL_WORKER\",\"jobId\":\"%s\"}".formatted(job))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_NOT_ON_A_WORKER"));
        fixture.retire(worker.id());
        createAsAdmin("{\"fault\":\"KILL_WORKER\",\"workerId\":\"%s\"}".formatted(worker.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKER_NOT_ACTIVE"));
    }

    @Test
    void aimingAtAJob_findsTheWorkerRunningIt() throws Exception {
        // Two candidates: the experiment must land on whichever one the scheduler picked, not on the first one.
        fixture.worker("first", 1);
        fixture.worker("second", 1);
        var job = fixture.submit(3);
        fixture.place();
        var holder = jdbc.sql("SELECT worker_id FROM job_attempts WHERE job_id = :j")
                .param("j", job)
                .query(UUID.class)
                .single();

        createAsAdmin("{\"fault\":\"PAUSE_HEARTBEAT\",\"jobId\":\"%s\"}".formatted(job))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerId").value(holder.toString()))
                .andExpect(jsonPath("$.jobId").value(job.toString()))
                .andExpect(jsonPath("$.parameters.durationMs").value(30_000));
    }

    @Test
    void undeliveredExperiments_expire_andAreNeverDeliveredLate() throws Exception {
        var worker = fixture.worker("target", 2);
        var id = body(createAsAdmin("{\"fault\":\"STOP_CLAIMING\",\"workerId\":\"%s\"}".formatted(worker.id())))
                .get("id")
                .asString();
        jdbc.sql("UPDATE chaos_experiments SET deliver_by = now() - interval '1 second' WHERE id = :id::uuid")
                .param("id", id)
                .update();

        heartbeat(worker).andExpect(jsonPath("$.chaos.length()").value(0));
        experiment(id)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.timeline.entries[1].type").value("EXPIRED"));
    }

    @Test
    void onlyAPendingExperimentCanBeCancelled() throws Exception {
        var worker = fixture.worker("target", 2);
        var cancelled = body(createAsAdmin("{\"fault\":\"STOP_CLAIMING\",\"workerId\":\"%s\"}".formatted(worker.id())))
                .get("id")
                .asString();
        var delivered = body(createAsAdmin("{\"fault\":\"STALL_ATTEMPTS\",\"workerId\":\"%s\"}".formatted(worker.id())))
                .get("id")
                .asString();
        cancel(cancelled)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        heartbeat(worker).andExpect(jsonPath("$.chaos.length()").value(1));

        cancel(delivered)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPERIMENT_NOT_PENDING"));
        cancel(cancelled).andExpect(status().isConflict());
    }

    /**
     * Twenty operators racing to queue faults on one worker: the advisory lock makes the count and the insert one
     * step, so exactly the limit gets in. Without it, several requests would all count below the limit.
     */
    @Test
    void concurrentCreates_neverExceedThePendingLimit() throws Exception {
        var worker = fixture.worker("target", 2);
        var created = new ConcurrentLinkedQueue<ChaosExperiment>();
        var tasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                try {
                    created.add(experiments.create(
                            new Caller.Admin(),
                            WorkerProtocol.ChaosFault.STALL_ATTEMPTS,
                            new ChaosExperiments.Target(worker.id(), null),
                            new FaultParameters(0, 0, 1, 0, 0)));
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("TOO_MANY_PENDING_FAULTS");
                }
                return null;
            });
        }

        runConcurrently(tasks);

        assertThat(created).hasSize(5);
        assertThat(count("SELECT count(*) FROM chaos_experiments WHERE status = 'PENDING'"))
                .isEqualTo(5);
    }

    /** Overlapping heartbeats of one worker (a retry after a timeout) must not deliver a fault twice. */
    @Test
    void concurrentHeartbeats_deliverEachFaultExactlyOnce() throws Exception {
        for (int round = 0; round < 10; round++) {
            fixture.reset();
            var worker = fixture.worker("target", 2);
            for (int i = 0; i < 5; i++) {
                createAsAdmin("{\"fault\":\"PROVIDER_ERROR\",\"workerId\":\"%s\"}".formatted(worker.id()))
                        .andExpect(status().isCreated());
            }
            var delivered = new ConcurrentLinkedQueue<UUID>();
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    experiments.deliver(worker.id()).forEach(directive -> delivered.add(directive.experimentId()));
                    return null;
                });
            }

            runConcurrently(tasks);

            assertThat(delivered).hasSize(5).doesNotHaveDuplicates();
        }
    }

    /**
     * The recovery story the brief asks to see: a heartbeat pause hits the worker running a job, its lease expires,
     * the reaper recovers the attempt, and the job succeeds on another worker. The timeline is built from the records
     * alone, in order.
     */
    @Test
    void timeline_showsDetectionReschedulingAndRecovery() throws Exception {
        var victim = fixture.worker("victim", 1);
        var job = fixture.submit(3);
        fixture.place();
        var lostAttempt = fixture.latestAttempt(job);
        attempts.claim(victim.id(), 1);
        var id = body(createAsAdmin("{\"fault\":\"PAUSE_HEARTBEAT\",\"jobId\":\"%s\"}".formatted(job)))
                .get("id")
                .asString();
        heartbeat(victim, lostAttempt).andExpect(jsonPath("$.chaos[0].fault").value("PAUSE_HEARTBEAT"));

        // The worker stops heartbeating; its lease runs out and the reaper recovers the attempt.
        fixture.expireLease(lostAttempt);
        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);
        var rescuer = fixture.worker("rescuer", 1);
        fixture.retire(victim.id());
        fixture.makeRunnableNow(job);
        fixture.place();
        var retry = fixture.latestAttempt(job);
        attempts.claim(rescuer.id(), 1);
        attempts.report(rescuer.id(), retry, AttemptOutcome.SUCCEEDED, null, null, null, null);

        var response = body(experiment(id).andExpect(status().isOk()));

        var types = new ArrayList<String>();
        response.at("/timeline/entries")
                .forEach(entry -> types.add(entry.get("type").asString()));
        assertThat(types)
                .containsSubsequence(
                        "CREATED", "DELIVERED", "ATTEMPT_LOST", "RETRY_SCHEDULED", "SCHEDULED", "STARTED", "SUCCEEDED");
        var rescheduled = findEntry(response, "SCHEDULED");
        assertThat(rescheduled.get("workerId").asString())
                .isEqualTo(rescuer.id().toString());
        assertThat(response.at("/timeline/targetLifecycle").asString()).isEqualTo("OFFLINE");
        assertThat(response.at("/timeline/summary/affectedJobs").asInt()).isEqualTo(1);
        assertThat(response.at("/timeline/summary/recoveredJobs").asInt()).isEqualTo(1);
        var recovery = response.at("/timeline/jobs/0");
        assertThat(recovery.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(recovery.get("recoveryMs").asLong())
                .isGreaterThanOrEqualTo(recovery.get("detectionMs").asLong())
                .isGreaterThanOrEqualTo(0);
    }

    private static JsonNode findEntry(JsonNode response, String type) {
        for (var entry : response.at("/timeline/entries")) {
            if (entry.get("type").asString().equals(type)) {
                return entry;
            }
        }
        throw new AssertionError("No " + type + " entry");
    }

    private ResultActions createAsAdmin(String body) throws Exception {
        return create(ApiTestSupport.adminBearer(), body);
    }

    private ResultActions create(String bearer, String body) throws Exception {
        return mvc.perform(post("/api/v1/chaos/experiments")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions experiment(String id) throws Exception {
        return mvc.perform(
                get("/api/v1/chaos/experiments/" + id).header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()));
    }

    private ResultActions cancel(String id) throws Exception {
        return mvc.perform(post("/api/v1/chaos/experiments/" + id + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()));
    }

    private ResultActions heartbeat(ExecutionFixture.RegisteredWorker worker, UUID... running) throws Exception {
        var ids = new StringBuilder();
        for (var id : running) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/heartbeat")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activeAttemptIds\":[" + ids + "]}"));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Void>>();
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            for (var task : tasks) {
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
}
