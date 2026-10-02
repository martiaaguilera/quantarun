package io.github.martiaaguilera.quantarun.controlplane.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Pure decision table from docs/SPEC.md §8; seeded randomness keeps every jittered case reproducible. */
class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void cancelRequest_winsOverEverything() {
        for (var failureClass : FailureClass.values()) {
            assertThat(policy.decide(failureClass, 1, 3, true, new SplittableRandom(1)))
                    .isEqualTo(new RetryPolicy.Decision.GiveUp(
                            JobStatus.CANCELLED, "cancel requested while the attempt was active"));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureClass.class,
            names = {"INVALID_INPUT", "NON_RETRYABLE"})
    void nonRetryableClasses_failImmediately_evenWithBudgetLeft(FailureClass failureClass) {
        var decision = policy.decide(failureClass, 1, 10, false, new SplittableRandom(1));

        assertThat(decision)
                .isInstanceOfSatisfying(
                        RetryPolicy.Decision.GiveUp.class,
                        giveUp -> assertThat(giveUp.terminalStatus()).isEqualTo(JobStatus.FAILED));
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureClass.class,
            names = {"INVALID_INPUT", "NON_RETRYABLE"},
            mode = EnumSource.Mode.EXCLUDE)
    void retryableClasses_retryWhileBudgetRemains_thenGoDead(FailureClass failureClass) {
        for (int maxAttempts = 1; maxAttempts <= 10; maxAttempts++) {
            for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
                var decision =
                        policy.decide(failureClass, attemptNo, maxAttempts, false, new SplittableRandom(attemptNo));
                if (attemptNo < maxAttempts) {
                    assertThat(decision).isInstanceOf(RetryPolicy.Decision.Retry.class);
                } else {
                    assertThat(decision)
                            .as("attempt %d of %d", attemptNo, maxAttempts)
                            .isInstanceOfSatisfying(
                                    RetryPolicy.Decision.GiveUp.class,
                                    giveUp ->
                                            assertThat(giveUp.terminalStatus()).isEqualTo(JobStatus.DEAD));
                }
            }
        }
    }

    @Test
    void workerLost_retriesWithoutDelay() {
        assertThat(policy.decide(FailureClass.WORKER_LOST, 2, 3, false, new SplittableRandom(7)))
                .isEqualTo(new RetryPolicy.Decision.Retry(Duration.ZERO));
    }

    @Test
    void backoff_staysWithinTheExponentialCeiling_andTheCap() {
        var random = new SplittableRandom(42);
        for (int attemptNo = 1; attemptNo <= 40; attemptNo++) {
            var ceiling = Math.min(60_000L, 1_000L << Math.min(attemptNo - 1, 20));
            for (int sample = 0; sample < 200; sample++) {
                var delay = policy.fullJitterBackoff(attemptNo, random).toMillis();
                assertThat(delay).as("attempt %d", attemptNo).isBetween(0L, ceiling);
            }
        }
    }

    @Test
    void backoff_isJittered_andReproducibleForTheSameSeed() {
        var first = policy.fullJitterBackoff(5, new SplittableRandom(99));
        var again = policy.fullJitterBackoff(5, new SplittableRandom(99));
        var distinct = new java.util.HashSet<Duration>();
        var random = new SplittableRandom(99);
        for (int i = 0; i < 50; i++) {
            distinct.add(policy.fullJitterBackoff(5, random));
        }

        assertThat(first).isEqualTo(again);
        assertThat(distinct).hasSizeGreaterThan(40);
    }
}
