package io.github.martiaaguilera.quantarun.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofMillis(500), Duration.ofSeconds(30));

    @Test
    void delay_neverExceedsTheGrowingCeiling() {
        var random = RandomGenerator.of("L64X128MixRandom");
        for (int failures = 1; failures <= 200; failures++) {
            var ceiling = Math.min(30_000L, 500L << Math.min(failures - 1, 20));
            for (int sample = 0; sample < 50; sample++) {
                assertThat(backoff.delayForFailure(failures, random).toMillis()).isBetween(0L, ceiling);
            }
        }
    }

    @Test
    void longOutages_doNotOverflowTheExponent() {
        var random = RandomGenerator.of("L64X128MixRandom");
        assertThat(backoff.delayForFailure(Integer.MAX_VALUE, random)).isBetween(Duration.ZERO, Duration.ofSeconds(30));
    }
}
