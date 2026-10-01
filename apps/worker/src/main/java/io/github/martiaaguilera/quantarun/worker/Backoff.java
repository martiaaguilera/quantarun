package io.github.martiaaguilera.quantarun.worker;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter. When the control plane restarts, every worker reconnects; without jitter they
 * would all retry in lock-step and hit it in synchronised waves.
 */
record Backoff(Duration base, Duration max) {

    Duration delayForFailure(int consecutiveFailures, RandomGenerator random) {
        // Cap the exponent before shifting so a long outage cannot overflow the multiplication.
        var exponent = Math.min(Math.max(consecutiveFailures - 1, 0), 20);
        var ceiling = Math.min(max.toMillis(), base.toMillis() << exponent);
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }
}
