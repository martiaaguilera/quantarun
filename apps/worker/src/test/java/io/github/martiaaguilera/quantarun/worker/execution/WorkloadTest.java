package io.github.martiaaguilera.quantarun.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The built-in workloads in isolation: payload validation, deterministic results, and reaction to interruption. */
class WorkloadTest {

    @Nested
    class Delay {

        @Test
        void sleepsAndReportsTheDuration() throws Exception {
            assertThat(new DelayWorkload()
                            .execute(new Payload(Map.of("durationMs", 5)), AttemptContext.withoutCheckpoints(1)))
                    .containsEntry("sleptMs", 5L);
        }

        @Test
        void rejectsMissingNegativeOrOversizedDurations() {
            var delay = new DelayWorkload();
            assertInvalid(() -> delay.execute(new Payload(Map.of()), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(
                    () -> delay.execute(new Payload(Map.of("durationMs", -1)), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(() ->
                    delay.execute(new Payload(Map.of("durationMs", 600_001)), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(
                    () -> delay.execute(new Payload(Map.of("durationMs", "10")), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(
                    () -> delay.execute(new Payload(Map.of("durationMs", 1.5)), AttemptContext.withoutCheckpoints(1)));
        }

        @Test
        void stopsWhenInterrupted() throws Exception {
            var started = new CountDownLatch(1);
            var outcome = new CompletableFuture<Throwable>();
            var thread = Thread.ofVirtual().start(() -> {
                started.countDown();
                try {
                    new DelayWorkload()
                            .execute(new Payload(Map.of("durationMs", 600_000)), AttemptContext.withoutCheckpoints(1));
                    outcome.complete(null);
                } catch (Throwable e) {
                    outcome.complete(e);
                }
            });
            started.await();
            thread.interrupt();

            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        }
    }

    @Nested
    class CpuHash {

        @Test
        void chainsSha256Deterministically() throws Exception {
            var expected = MessageDigest.getInstance("SHA-256")
                    .digest(MessageDigest.getInstance("SHA-256").digest("seed".getBytes(StandardCharsets.UTF_8)));

            var result = new CpuHashWorkload()
                    .execute(
                            new Payload(Map.of("iterations", 2, "seed", "seed")), AttemptContext.withoutCheckpoints(1));

            assertThat(result)
                    .containsEntry("iterations", 2L)
                    .containsEntry("sha256", HexFormat.of().formatHex(expected));
        }

        @Test
        void stopsWhenInterrupted() throws Exception {
            var outcome = new CompletableFuture<Throwable>();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    new CpuHashWorkload()
                            .execute(
                                    new Payload(Map.of("iterations", CpuHashWorkload.MAX_ITERATIONS)),
                                    AttemptContext.withoutCheckpoints(1));
                    outcome.complete(null);
                } catch (Throwable e) {
                    outcome.complete(e);
                }
            });
            thread.interrupt();

            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        }

        @Test
        void boundsTheIterationCount() {
            assertInvalid(() -> new CpuHashWorkload()
                    .execute(new Payload(Map.of("iterations", 0)), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(() -> new CpuHashWorkload()
                    .execute(
                            new Payload(Map.of("iterations", BigInteger.TEN.pow(30))),
                            AttemptContext.withoutCheckpoints(1)));
        }
    }

    @Nested
    class MockInference {

        private final Map<String, Object> payload =
                Map.of("inputTokens", 120, "outputTokens", 40, "latencyMs", 1, "seed", 7);

        @Test
        void reportsTokenCountsLikeAProvider() throws Exception {
            var result = new MockInferenceWorkload(ChaosInjector.disabled())
                    .execute(new Payload(payload), AttemptContext.withoutCheckpoints(1));

            assertThat(result)
                    .containsEntry("model", "mock")
                    .containsEntry("inputTokens", 120L)
                    .containsEntry("outputTokens", 40L)
                    .containsEntry("totalTokens", 160L)
                    .containsEntry("latencyMs", 1L);
            assertThat((Iterable<?>) result.get("previewTokenIds")).hasSize(8);
        }

        @Test
        void isDeterministicForTheSamePayload_andDiffersForAnotherSeed() throws Exception {
            var workload = new MockInferenceWorkload(ChaosInjector.disabled());
            var first = workload.execute(new Payload(payload), AttemptContext.withoutCheckpoints(1));
            var retry = workload.execute(new Payload(payload), AttemptContext.withoutCheckpoints(2));
            var other = workload.execute(
                    new Payload(Map.of("inputTokens", 120, "outputTokens", 40, "latencyMs", 1, "seed", 8)),
                    AttemptContext.withoutCheckpoints(1));

            assertThat(retry).isEqualTo(first);
            assertThat(other.get("outputDigest")).isNotEqualTo(first.get("outputDigest"));
        }

        @Test
        void requiresTheTokenCounts() {
            assertInvalid(() -> new MockInferenceWorkload(ChaosInjector.disabled())
                    .execute(
                            new Payload(Map.of("outputTokens", 1, "latencyMs", 0)),
                            AttemptContext.withoutCheckpoints(1)));
        }
    }

    @Nested
    class Fail {

        @Test
        void failsWithTheRequestedClass() {
            assertThatThrownBy(() -> new FailWorkload()
                            .execute(
                                    new Payload(Map.of("failureClass", "rate_limited", "message", "429")),
                                    AttemptContext.withoutCheckpoints(1)))
                    .isInstanceOfSatisfying(WorkloadFailure.class, failure -> {
                        assertThat(failure.failureClass()).isEqualTo(FailureClass.RATE_LIMITED);
                        assertThat(failure.getMessage()).isEqualTo("429");
                    });
        }

        @Test
        void succeedsFromTheConfiguredAttemptOn() {
            var payload = new Payload(Map.of("failureClass", "TRANSIENT", "succeedOnAttempt", 3));
            var fail = new FailWorkload();

            assertThatThrownBy(() -> fail.execute(payload, AttemptContext.withoutCheckpoints(2)))
                    .isInstanceOf(WorkloadFailure.class);
            assertThat(fail.execute(payload, AttemptContext.withoutCheckpoints(3)))
                    .containsEntry("succeededOnAttempt", 3);
        }

        @Test
        void cannotImpersonateTheControlPlanesVerdict() {
            assertInvalid(() -> new FailWorkload()
                    .execute(new Payload(Map.of("failureClass", "WORKER_LOST")), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(() -> new FailWorkload()
                    .execute(new Payload(Map.of("failureClass", "NOPE")), AttemptContext.withoutCheckpoints(1)));
            assertInvalid(
                    () -> new FailWorkload().execute(new Payload(Map.of()), AttemptContext.withoutCheckpoints(1)));
        }
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.INVALID_INPUT));
    }
}
