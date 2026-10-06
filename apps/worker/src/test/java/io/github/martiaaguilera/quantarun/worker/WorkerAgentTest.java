package io.github.martiaaguilera.quantarun.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptExecutor;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptTelemetry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Drives {@link WorkerAgent#step()} one iteration at a time against a scripted control plane: no threads, no sleeps. */
class WorkerAgentTest {

    private static final String BOOTSTRAP = "bootstrap-token-0123456789-0123456789";
    private static final String BASE = "http://control-plane.test";
    private static final String WORKER_ID = "01a0f358-0000-7000-8000-000000000001";
    private static final String SECRET = "qw_00000001_" + "S".repeat(43);

    private final CountDownLatch halted = new CountDownLatch(1);
    private final ChaosInjector chaos = new ChaosInjector(true, Clock.systemUTC(), halted::countDown);
    private MockRestServiceServer server;
    private WorkerAgent agent;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var settings = new WorkerSettings(
                URI.create(BASE),
                BOOTSTRAP,
                "gpu-node",
                List.of("cuda"),
                new WorkerSettings.Capacity(8000, 16384, 2, 4),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMillis(500),
                Duration.ZERO,
                Duration.ofSeconds(5),
                3,
                List.of(),
                true);
        var client = new ControlPlaneClient(builder.build(), BOOTSTRAP);
        // A fixed seed makes the jittered backoff reproducible in assertions.
        agent = new WorkerAgent(
                client,
                new AttemptExecutor(
                        client,
                        4,
                        new AttemptExecutor.ReportPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                        new AttemptExecutor.HttpSettings(List.of(), Duration.ofSeconds(1)),
                        ChaosInjector.disabled(),
                        AttemptTelemetry.noop(),
                        RandomGenerator.of("L64X128MixRandom")),
                settings,
                chaos,
                "test",
                RandomGenerator.of("L64X128MixRandom"));
    }

    @Test
    void firstStep_registersWithBootstrapTokenAndDeclaredCapacity() {
        expectRegistration();

        var delay = agent.step();

        assertThat(delay).isZero();
        assertThat(agent.workerId())
                .hasValueSatisfying(id -> assertThat(id.toString()).isEqualTo(WORKER_ID));
        server.verify();
    }

    @Test
    void afterRegistration_heartbeatsWithItsOwnCredentialAtTheIntervalTheControlPlaneSets() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess(
                        "{\"lifecycle\":\"ACTIVE\",\"cancelAttemptIds\":[],\"lostAttemptIds\":[]}",
                        MediaType.APPLICATION_JSON));

        agent.step();
        var delay = agent.step();

        assertThat(delay).isEqualTo(Duration.ofMillis(3000));
        server.verify();
    }

    @Test
    void retiredRegistration_isAbandonedAndTheWorkerRegistersAgain() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat")).andRespond(withStatus(HttpStatus.CONFLICT));
        expectRegistration();

        agent.step();
        var afterConflict = agent.step();
        assertThat(agent.workerId()).isEmpty();
        assertThat(afterConflict).isZero();

        agent.step();
        assertThat(agent.workerId()).isPresent();
        server.verify();
    }

    @Test
    void drainingIsReportedToTheRestOfTheWorker() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andRespond(withSuccess(
                        "{\"lifecycle\":\"DRAINING\",\"cancelAttemptIds\":[],\"lostAttemptIds\":[]}",
                        MediaType.APPLICATION_JSON));

        agent.step();
        agent.step();

        assertThat(agent.lifecycle().name()).isEqualTo("DRAINING");
    }

    @Test
    void controlPlaneOutage_backsOffWithinTheCapInsteadOfHammering() {
        for (int i = 0; i < 12; i++) {
            server.expect(requestTo(BASE + "/worker-api/v1/register")).andRespond(withServerError());
        }

        Duration maxSeen = Duration.ZERO;
        for (int i = 0; i < 12; i++) {
            var delay = agent.step();
            assertThat(delay).isBetween(Duration.ZERO, Duration.ofSeconds(30));
            if (delay.compareTo(maxSeen) > 0) {
                maxSeen = delay;
            }
        }
        assertThat(agent.workerId()).isEmpty();
        // Full jitter is random by design, but with the growing ceiling some retries must wait well past the base.
        assertThat(maxSeen).isGreaterThan(Duration.ofSeconds(1));
        server.verify();
    }

    @Test
    void deregister_tellsTheControlPlaneAndForgetsTheRegistration() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/deregister"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("{\"lifecycle\":\"DEREGISTERED\"}", MediaType.APPLICATION_JSON));

        agent.step();
        agent.deregister();

        assertThat(agent.workerId()).isEmpty();
        server.verify();
    }

    /** The fault arrives in a heartbeat response; from then on no heartbeat leaves until the pause ends. */
    @Test
    void pauseHeartbeatChaos_stopsHeartbeatsButNotTheWorker() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andRespond(withSuccess(heartbeatWithChaos("PAUSE_HEARTBEAT"), MediaType.APPLICATION_JSON));

        agent.step();
        agent.step();
        var paused = agent.step();

        assertThat(paused).isEqualTo(Duration.ofMillis(3000));
        assertThat(agent.workerId()).isPresent();
        // Only the one heartbeat that carried the fault was expected: a second request would fail verification.
        server.verify();
    }

    @Test
    void stopClaimingChaos_leavesAssignmentsUnclaimed() {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andRespond(withSuccess(heartbeatWithChaos("STOP_CLAIMING"), MediaType.APPLICATION_JSON));

        agent.step();
        agent.step();
        var delay = agent.claimStep();

        assertThat(delay).isEqualTo(Duration.ofMillis(500));
        server.verify();
    }

    @Test
    void killChaos_haltsTheProcessAfterItsDelay() throws InterruptedException {
        expectRegistration();
        server.expect(requestTo(BASE + "/worker-api/v1/heartbeat"))
                .andRespond(withSuccess(heartbeatWithChaos("KILL_WORKER"), MediaType.APPLICATION_JSON));

        agent.step();
        agent.step();

        assertThat(halted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static String heartbeatWithChaos(String fault) {
        return """
                {"lifecycle":"ACTIVE","cancelAttemptIds":[],"lostAttemptIds":[],
                 "chaos":[{"experimentId":"01a0f358-0000-7000-8000-0000000000c1","fault":"%s","delayMillis":10,
                           "durationMillis":30000,"count":0,"retryAfterMillis":0,"latencyMillis":0}]}
                """.formatted(fault);
    }

    private void expectRegistration() {
        server.expect(requestTo(BASE + "/worker-api/v1/register"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + BOOTSTRAP))
                .andExpect(jsonPath("$.name").value("gpu-node"))
                .andExpect(jsonPath("$.capacity.accelerators").value(2))
                .andExpect(jsonPath("$.labels[0]").value("cuda"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"workerId\":\"" + WORKER_ID + "\",\"workerSecret\":\"" + SECRET
                                + "\",\"heartbeatIntervalMillis\":3000,\"leaseDurationMillis\":15000}"));
    }
}
