package io.github.martiaaguilera.quantarun.controlplane.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class WorkerFleetTest {

    private static final String BOOTSTRAP = "Bearer " + IntegrationTest.WORKER_BOOTSTRAP_TOKEN;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WorkerRegistry registry;

    record Registered(UUID id, String secret) {
        String bearer() {
            return "Bearer " + secret;
        }
    }

    @Test
    void register_issuesAPerWorkerCredentialAndTiming() throws Exception {
        register("gpu-node", 8000, 16384, 2, 4, "\"cuda\",\"large-model\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerSecret", startsWith("qw_")))
                .andExpect(jsonPath("$.heartbeatIntervalMillis").value(3000))
                .andExpect(jsonPath("$.leaseDurationMillis").value(15000));
    }

    @Test
    void register_requiresTheBootstrapToken() throws Exception {
        mvc.perform(post(WorkerProtocol.BASE_PATH + "/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token-wrong-token-wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("w", 1000, 1024, 0, 1, "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WORKER_UNAUTHENTICATED"));
    }

    @Test
    void credentialsDoNotCrossBoundaries() throws Exception {
        var worker = registered("boundary-worker");
        var project = new ApiTestSupport(mvc, json).createProject();

        // A worker credential is not a client credential, and the other way round.
        mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, worker.bearer()))
                .andExpect(status().isUnauthorized());
        heartbeat(project.bearer()).andExpect(status().isUnauthorized());
        // The bootstrap token can register, but cannot act as a worker.
        heartbeat(BOOTSTRAP).andExpect(status().isForbidden());
        // A registered worker cannot register others with its own credential.
        mvc.perform(post(WorkerProtocol.BASE_PATH + "/register")
                        .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("sneaky", 1000, 1024, 0, 1, "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void heartbeat_isRecordedOnlyForTheAuthenticatedWorker() throws Exception {
        var a = registered("worker-a");
        var b = registered("worker-b");

        heartbeat(a.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"));
        heartbeat(a.bearer()).andExpect(status().isOk());

        assertThat(beats(a.id())).isEqualTo(3);
        assertThat(beats(b.id())).isEqualTo(1);
    }

    @Test
    void silentWorker_isRetiredAndMustRegisterAgain() throws Exception {
        var silent = registered("silent-worker");
        var chatty = registered("chatty-worker");
        backdateLastSeen(silent.id(), "20 seconds");

        var retired = registry.retireSilentWorkers();

        assertThat(retired).extracting(r -> r.id()).contains(silent.id()).doesNotContain(chatty.id());
        assertThat(lifecycle(silent.id())).isEqualTo("OFFLINE");
        heartbeat(silent.bearer())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKER_NOT_ACTIVE"));
        // Retiring is idempotent: a second pass finds nothing new to retire for this worker.
        assertThat(registry.retireSilentWorkers()).extracting(r -> r.id()).doesNotContain(silent.id());
    }

    @Test
    void oneMissedHeartbeat_changesNothing() throws Exception {
        var worker = registered("blip-worker");
        backdateLastSeen(worker.id(), "4 seconds");

        assertThat(registry.retireSilentWorkers()).extracting(r -> r.id()).doesNotContain(worker.id());
        mvc.perform(get("/api/v1/workers/" + worker.id())
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(jsonPath("$.health").value("HEALTHY"))
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"));
    }

    @Test
    void lateWorker_isReportedLateButStaysActive() throws Exception {
        var worker = registered("late-worker");
        backdateLastSeen(worker.id(), "10 seconds");

        assertThat(registry.retireSilentWorkers()).extracting(r -> r.id()).doesNotContain(worker.id());
        mvc.perform(get("/api/v1/workers/" + worker.id())
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(jsonPath("$.health").value("LATE"))
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"));
    }

    @Test
    void drain_isAdminOnlyAndVisibleToTheWorker() throws Exception {
        var worker = registered("drain-worker");
        var project = new ApiTestSupport(mvc, json).createProject();

        mvc.perform(post("/api/v1/workers/" + worker.id() + "/drain")
                        .header(HttpHeaders.AUTHORIZATION, project.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workers/" + worker.id() + "/drain")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DRAINING"));

        heartbeat(worker.bearer()).andExpect(jsonPath("$.lifecycle").value("DRAINING"));
    }

    @Test
    void deregister_withNothingReserved_leavesImmediately() throws Exception {
        var worker = registered("leaving-worker");

        mvc.perform(post(WorkerProtocol.BASE_PATH + "/deregister").header(HttpHeaders.AUTHORIZATION, worker.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEREGISTERED"));
        heartbeat(worker.bearer()).andExpect(status().isConflict());
    }

    @Test
    void deregister_whileHoldingReservations_drainsFirst() throws Exception {
        var worker = registered("busy-worker");
        jdbc.sql("UPDATE workers SET slots_reserved = 1, cpu_millis_reserved = 100 WHERE id = :id")
                .param("id", worker.id())
                .update();

        mvc.perform(post(WorkerProtocol.BASE_PATH + "/deregister").header(HttpHeaders.AUTHORIZATION, worker.bearer()))
                .andExpect(jsonPath("$.lifecycle").value("DRAINING"));
    }

    /** Invariant I1, database guard: even a buggy scheduler cannot persist an overcommitted worker. */
    @Test
    void database_rejectsReservationsBeyondCapacityOrBelowZero() throws Exception {
        var worker = registered("guarded-worker");

        assertThatThrownBy(() -> reserve(worker.id(), "cpu_millis_reserved", 1001))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reserve(worker.id(), "memory_mib_reserved", 1025))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reserve(worker.id(), "accelerators_reserved", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reserve(worker.id(), "slots_reserved", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reserve(worker.id(), "slots_reserved", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        reserve(worker.id(), "cpu_millis_reserved", 1000);
    }

    @Test
    void invalidRegistration_isRejectedField() throws Exception {
        register("Bad Name!", 0, 1024, 0, 1, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Registered registered(String name) throws Exception {
        var body = json.readTree(register(name, 1000, 1024, 0, 2, "")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return new Registered(
                UUID.fromString(body.get("workerId").asString()),
                body.get("workerSecret").asString());
    }

    private ResultActions register(String name, int cpu, int memory, int accelerators, int slots, String labels)
            throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/register")
                .header(HttpHeaders.AUTHORIZATION, BOOTSTRAP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(name, cpu, memory, accelerators, slots, labels)));
    }

    private static String registrationBody(
            String name, int cpu, int memory, int accelerators, int slots, String labels) {
        return """
                {"name":"%s","version":"test","labels":[%s],
                 "capacity":{"cpuMillis":%d,"memoryMib":%d,"accelerators":%d,"slots":%d}}
                """.formatted(name, labels, cpu, memory, accelerators, slots);
    }

    private ResultActions heartbeat(String bearer) throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/heartbeat")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activeAttemptIds\":[]}"));
    }

    private long beats(UUID workerId) {
        return jdbc.sql("SELECT beats FROM worker_heartbeats WHERE worker_id = :id")
                .param("id", workerId)
                .query(Long.class)
                .single();
    }

    private String lifecycle(UUID workerId) {
        return jdbc.sql("SELECT lifecycle FROM workers WHERE id = :id")
                .param("id", workerId)
                .query(String.class)
                .single();
    }

    /** Moves the last heartbeat into the past instead of sleeping through the threshold. */
    private void backdateLastSeen(UUID workerId, String age) {
        jdbc.sql("UPDATE worker_heartbeats SET last_seen_at = now() - CAST(:age AS interval) WHERE worker_id = :id")
                .param("age", age)
                .param("id", workerId)
                .update();
    }

    private void reserve(UUID workerId, String column, int value) {
        jdbc.sql("UPDATE workers SET " + column + " = :value WHERE id = :id")
                .param("value", value)
                .param("id", workerId)
                .update();
    }
}
