package io.github.martiaaguilera.quantarun.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptExecutor;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The agent's claim → execute → report cycle and its reactions to heartbeat responses, against a scripted control
 * plane. The agent's steps run on the test thread; executions run on the executor's threads and the test waits for
 * them with a bounded {@code awaitIdle}, never a sleep.
 */
class WorkerAgentExecutionTest {

    private static final String BOOTSTRAP = "bootstrap-token-0123456789-0123456789";
    private static final String BASE = "http://control-plane.test";
    private static final String WORKER_ID = "01a0f358-0000-7000-8000-000000000001";
    private static final String SECRET = "qw_00000001_" + "S".repeat(43);
    private static final String ATTEMPT_A = "01a0f358-0000-7000-8000-00000000000a";
    private static final String ATTEMPT_B = "01a0f358-0000-7000-8000-00000000000b";
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private MockRestServiceServer server;
    private AttemptExecutor executor;
    private WorkerAgent agent;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var client = new ControlPlaneClient(builder.build(), BOOTSTRAP);
        var settings = new WorkerSettings(
                URI.create(BASE),
                BOOTSTRAP,
                "cpu-node",
                List.of(),
                new WorkerSettings.Capacity(4000, 4096, 0, 2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMillis(500),
                Duration.ofSeconds(5),
                3,
                List.of(),
                true);
        executor = new AttemptExecutor(
                client,
                2,
                new AttemptExecutor.ReportPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                new AttemptExecutor.HttpSettings(List.of(), Duration.ofSeconds(1)),
                ChaosInjector.disabled(),
                RandomGenerator.of("L64X128MixRandom"));
        agent = new WorkerAgent(
                client, executor, settings, ChaosInjector.disabled(), "test", RandomGenerator.of("L64X128MixRandom"));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void claimedAssignment_isExecutedAndReported_underThisWorkersCredential() throws Exception {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/claim"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(jsonPath("$.maxAssignments").value(2))
                .andRespond(assignments(assignment(
                        ATTEMPT_A,
                        "mock-inference",
                        "{\"inputTokens\":10,\"outputTokens\":5,\"latencyMs\":1,\"seed\":3}")));
        server.expect(requestTo(BASE + "/worker-api/v1/attempts/" + ATTEMPT_A + "/report"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.totalTokens").value(15))
                .andRespond(reportAccepted(ATTEMPT_A));

        agent.step();
        var delay = agent.claimStep();

        assertThat(delay).as("work was waiting, so ask again at once").isZero();
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void heartbeat_carriesRunningAttempts_andStopsTheLostAndCancelledOnes() throws Exception {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/claim"))
                .andRespond(assignments(
                        assignment(ATTEMPT_A, "delay", "{\"durationMs\":600000}"),
                        assignment(ATTEMPT_B, "delay", "{\"durationMs\":600000}")));
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andExpect(jsonPath("$.activeAttemptIds", Matchers.containsInAnyOrder(ATTEMPT_A, ATTEMPT_B)))
                .andRespond(withSuccess(
                        "{\"lifecycle\":\"ACTIVE\",\"cancelAttemptIds\":[\"" + ATTEMPT_B + "\"],\"lostAttemptIds\":[\""
                                + ATTEMPT_A + "\"]}",
                        MediaType.APPLICATION_JSON));
        // Only the cancelled attempt is reported; the lost one is silently dropped.
        server.expect(requestTo(BASE + "/worker-api/v1/attempts/" + ATTEMPT_B + "/report"))
                .andExpect(jsonPath("$.outcome").value("CANCELLED"))
                .andRespond(reportAccepted(ATTEMPT_B));

        agent.step();
        agent.claimStep();
        assertThat(agent.claimStep()).as("no free slot, no request").isEqualTo(Duration.ofMillis(500));
        agent.step();

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void retiredRegistration_abandonsItsAttemptsWithoutReporting_andRegistersAgain() throws Exception {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/claim"))
                .andRespond(assignments(assignment(ATTEMPT_A, "delay", "{\"durationMs\":600000}")));
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat")).andRespond(withStatus(HttpStatus.CONFLICT));
        expectRegistration();

        agent.step();
        agent.claimStep();
        agent.step();
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        agent.step();

        assertThat(agent.workerId()).isPresent();
        server.verify();
    }

    @Test
    void leaving_stopsClaiming_andNeverRegistersAgain() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/deregister"))
                .andRespond(withSuccess("{\"lifecycle\":\"DEREGISTERED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat")).andRespond(withStatus(HttpStatus.CONFLICT));

        agent.step();
        agent.beginLeaving();
        agent.claimStep();
        agent.step();
        agent.step();

        assertThat(agent.workerId()).isEmpty();
        server.verify();
    }

    private void expectRegistration() {
        server.expect(requestTo(BASE + "/worker-api/v1/register"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"workerId\":\"" + WORKER_ID + "\",\"workerSecret\":\"" + SECRET
                                + "\",\"heartbeatIntervalMillis\":3000,\"leaseDurationMillis\":15000}"));
    }

    private static String assignment(String attemptId, String type, String payload) {
        return "{\"attemptId\":\"" + attemptId + "\",\"jobId\":\"01a0f358-0000-7000-8000-0000000000ff\","
                + "\"attemptNo\":1,\"workloadType\":\"" + type + "\",\"payload\":" + payload
                + ",\"timeoutSeconds\":600}";
    }

    private static org.springframework.test.web.client.ResponseCreator assignments(String... items) {
        return withSuccess("{\"assignments\":[" + String.join(",", items) + "]}", MediaType.APPLICATION_JSON);
    }

    private static org.springframework.test.web.client.ResponseCreator reportAccepted(String attemptId) {
        return withSuccess(
                "{\"attemptId\":\"" + attemptId + "\",\"attemptStatus\":\"SUCCEEDED\",\"jobStatus\":\"SUCCEEDED\"}",
                MediaType.APPLICATION_JSON);
    }
}
