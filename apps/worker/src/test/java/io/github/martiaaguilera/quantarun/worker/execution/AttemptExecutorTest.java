package io.github.martiaaguilera.quantarun.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The executor against a scripted control plane. Executions run on their own threads, so each test waits for the
 * executor to go idle (a bounded wait on a monitor, not a sleep) before verifying the requests it made.
 */
class AttemptExecutorTest {

    private static final String BASE = "http://control-plane.test";
    private static final String SECRET = "qw_00000001_" + "S".repeat(43);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private MockRestServiceServer server;
    private final ChaosInjector chaos = new ChaosInjector(true, Clock.systemUTC(), () -> {});
    private AttemptExecutor executor;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        executor = new AttemptExecutor(
                new ControlPlaneClient(builder.build(), "bootstrap-token-0123456789-0123456789"),
                2,
                new AttemptExecutor.ReportPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                new AttemptExecutor.HttpSettings(List.of(), Duration.ofSeconds(1)),
                chaos,
                RandomGenerator.of("L64X128MixRandom"));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void success_isReportedWithTheWorkloadResult_andFreesTheSlot() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 1), 30);
        expectReport(attempt)
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.failureClass").doesNotExist())
                .andExpect(jsonPath("$.result.sleptMs").value(1))
                .andRespond(accepted(attempt, "SUCCEEDED"));

        executor.start(attempt, SECRET);
        assertThat(executor.freeSlots()).isLessThanOrEqualTo(1);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        assertThat(executor.freeSlots()).isEqualTo(2);
        server.verify();
    }

    @Test
    void classifiedFailure_isReportedWithItsClass() throws Exception {
        var attempt = assignment("fail", Map.of("failureClass", "RATE_LIMITED", "message", "slow down"), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureClass").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("slow down"))
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void invalidPayload_isReportedAsInvalidInput() throws Exception {
        var attempt = assignment("delay", Map.of(), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.failureClass").value("INVALID_INPUT"))
                .andRespond(accepted(attempt, "FAILED"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void unknownWorkloadType_isNonRetryable() throws Exception {
        var attempt = assignment("quantum-annealing", Map.of(), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.failureClass").value("NON_RETRYABLE"))
                .andRespond(accepted(attempt, "FAILED"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void attemptOutlivingItsTimeout_isStoppedAndReportedAsTimeout() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 600_000), 1);
        expectReport(attempt)
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureClass").value("TIMEOUT"))
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void stallChaos_hangsTheNextAttemptUntilItsTimeout_andOnlyThatOne() throws Exception {
        chaos.apply(List.of(directive(ChaosFault.STALL_ATTEMPTS, 1, 0)));
        var stalled = assignment("delay", Map.of("durationMs", 0), 1);
        var next = assignment("delay", Map.of("durationMs", 0), 1);
        expectReport(stalled)
                .andExpect(jsonPath("$.failureClass").value("TIMEOUT"))
                .andRespond(accepted(stalled, "RETRY_WAIT"));
        expectReport(next).andExpect(jsonPath("$.outcome").value("SUCCEEDED")).andRespond(accepted(next, "SUCCEEDED"));

        executor.start(stalled, SECRET);
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        executor.start(next, SECRET);
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();

        server.verify();
    }

    @Test
    void providerRateLimitChaos_isReportedAsRateLimited_withItsRetryAfter() throws Exception {
        chaos.apply(List.of(directive(ChaosFault.PROVIDER_RATE_LIMITED, 1, 7_000)));
        var attempt = assignment("mock-inference", Map.of("inputTokens", 10, "outputTokens", 10, "latencyMs", 0), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.failureClass").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterMillis").value(7_000))
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void providerErrorAndMalformedChaos_areTransient() throws Exception {
        chaos.apply(
                List.of(directive(ChaosFault.PROVIDER_ERROR, 1, 0), directive(ChaosFault.PROVIDER_MALFORMED, 1, 0)));
        var payload = Map.<String, Object>of("inputTokens", 10, "outputTokens", 10, "latencyMs", 0);
        var first = assignment("mock-inference", payload, 30);
        var second = assignment("mock-inference", payload, 30);
        expectReport(first)
                .andExpect(jsonPath("$.failureClass").value("TRANSIENT"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("HTTP 500")))
                .andRespond(accepted(first, "RETRY_WAIT"));
        expectReport(second)
                .andExpect(jsonPath("$.failureClass").value("TRANSIENT"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("could not be parsed")))
                .andRespond(accepted(second, "RETRY_WAIT"));

        executor.start(first, SECRET);
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        executor.start(second, SECRET);
        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();

        server.verify();
    }

    private static WorkerProtocol.ChaosDirective directive(ChaosFault fault, int count, long retryAfterMillis) {
        return new WorkerProtocol.ChaosDirective(UUID.randomUUID(), fault, 0, 0, count, retryAfterMillis, 0);
    }

    @Test
    void cancelledAttempt_stopsAndReportsCancelled() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 600_000), 600);
        expectReport(attempt)
                .andExpect(jsonPath("$.outcome").value("CANCELLED"))
                .andRespond(accepted(attempt, "CANCELLED"));

        executor.start(attempt, SECRET);
        assertThat(executor.runningAttemptIds()).containsExactly(attempt.attemptId());
        executor.cancel(attempt.attemptId());

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void lostAttempt_stopsWithoutReporting() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 600_000), 600);

        executor.start(attempt, SECRET);
        executor.abandon(attempt.attemptId());

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        // No expectation was set, so any report would have failed verification.
        server.verify();
    }

    @Test
    void shutdown_stopsRunningAttemptsAsTransientFailures() throws Exception {
        var attempt = assignment("cpu-hash", Map.of("iterations", 100_000_000), 600);
        expectReport(attempt)
                .andExpect(jsonPath("$.failureClass").value("TRANSIENT"))
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);
        assertThat(executor.awaitIdle(Duration.ofMillis(50))).isFalse();
        executor.stopAll();

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void reportFailingWithServerErrors_isRetriedWithinItsBudget() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 0), 30);
        server.expect(times(2), requestTo(reportUrl(attempt))).andRespond(withServiceUnavailable());
        expectReport(attempt).andRespond(accepted(attempt, "SUCCEEDED"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void reportRejectedAsNoLongerActive_isDroppedWithoutRetrying() throws Exception {
        var attempt = assignment("delay", Map.of("durationMs", 0), 30);
        server.expect(once(), requestTo(reportUrl(attempt)))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"ATTEMPT_NOT_ACTIVE\"}"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void slotsBoundConcurrentAttempts() {
        executor.start(assignment("delay", Map.of("durationMs", 600_000), 600), SECRET);
        executor.start(assignment("delay", Map.of("durationMs", 600_000), 600), SECRET);

        assertThat(executor.freeSlots()).isZero();
        assertThat(executor.runningAttemptIds()).hasSize(2);
        executor.abandonAll();
    }

    @Test
    void stagedAttempt_commitsEachStageThenReports() throws Exception {
        var attempt = assignment(
                "staged", Map.of("stages", java.util.List.of(Map.of("durationMs", 0), Map.of("durationMs", 0))), 30);
        for (int stage = 0; stage < 2; stage++) {
            server.expect(once(), requestTo(checkpointUrl(attempt)))
                    .andExpect(jsonPath("$.stageIndex").value(stage))
                    .andExpect(jsonPath("$.result.digest", Matchers.notNullValue()))
                    .andRespond(withSuccess(
                            "{\"stageIndex\":" + stage + ",\"alreadyCommitted\":false}", MediaType.APPLICATION_JSON));
        }
        expectReport(attempt)
                .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.executedStages").value(2))
                .andRespond(accepted(attempt, "SUCCEEDED"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void rejectedCheckpoint_stopsTheAttemptSilently() throws Exception {
        var attempt = assignment(
                "staged", Map.of("stages", java.util.List.of(Map.of("durationMs", 0), Map.of("durationMs", 0))), 30);
        server.expect(once(), requestTo(checkpointUrl(attempt)))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"ATTEMPT_NOT_ACTIVE\"}"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        // No second checkpoint and no report: the attempt was recovered, so this worker stays silent.
        server.verify();
    }

    @Test
    void rateLimitedFailure_carriesTheProvidersRetryAfter() throws Exception {
        var attempt = assignment("fail", Map.of("failureClass", "RATE_LIMITED", "retryAfterMillis", 15_000), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.failureClass").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterMillis").value(15_000))
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    @Test
    void retryAfter_isOnlySentForRateLimiting() throws Exception {
        var attempt = assignment("fail", Map.of("failureClass", "TRANSIENT", "retryAfterMillis", 15_000), 30);
        expectReport(attempt)
                .andExpect(jsonPath("$.retryAfterMillis").doesNotExist())
                .andRespond(accepted(attempt, "RETRY_WAIT"));

        executor.start(attempt, SECRET);

        assertThat(executor.awaitIdle(IDLE_TIMEOUT)).isTrue();
        server.verify();
    }

    private static String checkpointUrl(WorkerProtocol.Assignment attempt) {
        return BASE + "/worker-api/v1/attempts/" + attempt.attemptId() + "/checkpoints";
    }

    private org.springframework.test.web.client.ResponseActions expectReport(WorkerProtocol.Assignment attempt) {
        return server.expect(once(), requestTo(reportUrl(attempt)))
                .andExpect(jsonPath("$.outcome", Matchers.notNullValue()));
    }

    private static String reportUrl(WorkerProtocol.Assignment attempt) {
        return BASE + "/worker-api/v1/attempts/" + attempt.attemptId() + "/report";
    }

    private static org.springframework.test.web.client.ResponseCreator accepted(
            WorkerProtocol.Assignment attempt, String jobStatus) {
        return withSuccess(
                "{\"attemptId\":\"" + attempt.attemptId() + "\",\"attemptStatus\":\"X\",\"jobStatus\":\"" + jobStatus
                        + "\"}",
                MediaType.APPLICATION_JSON);
    }

    private static WorkerProtocol.Assignment assignment(String type, Map<String, Object> payload, int timeoutSeconds) {
        return new WorkerProtocol.Assignment(
                UUID.randomUUID(), UUID.randomUUID(), 1, type, payload, timeoutSeconds, null);
    }
}
