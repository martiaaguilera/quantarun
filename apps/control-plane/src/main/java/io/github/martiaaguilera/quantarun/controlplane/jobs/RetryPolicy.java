package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

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

    /** Upper bound on an honoured Retry-After: a provider asking for hours is treated as asking for this long. */
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(10);

    /**
     * @param attemptNo the attempt that just ended, counted within the current budget (1-based; a revive starts a new
     *     budget at 1)
     * @param cancelRequested a cancel request always wins: the job ends CANCELLED and is never retried (invariant I12)
     * @param retryAfter how long a rate-limited provider asked to wait, if it said; only used for RATE_LIMITED
     */
    public Decision decide(
            FailureClass failureClass,
            int attemptNo,
            int maxAttempts,
            boolean cancelRequested,
            @Nullable Duration retryAfter,
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
        var backoff = fullJitterBackoff(attemptNo, random);
        if (failureClass == FailureClass.RATE_LIMITED && retryAfter != null && !retryAfter.isNegative()) {
            // Retrying sooner than the provider asked only earns another 429.
            var honoured = retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
            return new Decision.Retry(honoured.compareTo(backoff) > 0 ? honoured : backoff);
        }
        return new Decision.Retry(backoff);
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
