package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Decides what happens to a job after an attempt ends without success (docs/SPEC.md §8, FAILURE_SEMANTICS.md). Pure:
 * the caller supplies the random source, so the same inputs and seed always give the same decision.
 */
public record RetryPolicy(Duration baseDelay, Duration maxDelay) {

    private static final Set<FailureClass> NON_RETRYABLE =
            EnumSet.of(FailureClass.INVALID_INPUT, FailureClass.NON_RETRYABLE);

    public sealed interface Decision {
        String describe();

        record Retry(Duration delay) implements Decision {
            @Override
            public String describe() {
                return "retry after " + delay.toMillis() + " ms";
            }
        }

        record GiveUp(JobStatus terminalStatus, String reason) implements Decision {
            @Override
            public String describe() {
                return terminalStatus + ": " + reason;
            }
        }
    }

    /**
     * @param attemptNo the attempt that just ended (1-based)
     * @param cancelRequested a cancel request always wins: the job ends CANCELLED and is never retried (invariant I12)
     */
    public Decision decide(
            FailureClass failureClass,
            int attemptNo,
            int maxAttempts,
            boolean cancelRequested,
            RandomGenerator random) {
        if (cancelRequested) {
            return new Decision.GiveUp(JobStatus.CANCELLED, "cancel requested while the attempt was active");
        }
        if (NON_RETRYABLE.contains(failureClass)) {
            return new Decision.GiveUp(JobStatus.FAILED, failureClass + " is not retryable");
        }
        // Invariant I9: the budget counts attempts, including the one that just ended.
        if (attemptNo >= maxAttempts) {
            return new Decision.GiveUp(
                    JobStatus.DEAD, "retry budget exhausted (" + attemptNo + " of " + maxAttempts + " attempts)");
        }
        if (failureClass == FailureClass.WORKER_LOST) {
            // The work itself did not fail; only its host did. Waiting would only add latency.
            return new Decision.Retry(Duration.ZERO);
        }
        return new Decision.Retry(fullJitterBackoff(attemptNo, random));
    }

    /**
     * Exponential backoff with full jitter: a random delay between zero and {@code base * 2^(attempt-1)}, capped. Full
     * jitter spreads retries of jobs that failed together (for example during a provider outage), so they do not all
     * come back at the same instant and fail again together.
     */
    Duration fullJitterBackoff(int attemptNo, RandomGenerator random) {
        var exponent = Math.min(Math.max(attemptNo - 1, 0), 20);
        var ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() << exponent);
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }
}
