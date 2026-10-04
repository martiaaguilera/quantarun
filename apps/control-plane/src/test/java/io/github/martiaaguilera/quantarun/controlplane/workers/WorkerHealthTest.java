package io.github.martiaaguilera.quantarun.controlplane.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkerHealthTest {

    private static final WorkerProperties DEFAULTS = new WorkerProperties(
            "x".repeat(32),
            Duration.ofSeconds(3),
            Duration.ofSeconds(7),
            Duration.ofSeconds(15),
            Duration.ofSeconds(15),
            Duration.ofSeconds(1),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30));
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @ParameterizedTest(name = "{0} ms of silence -> {1}")
    @CsvSource({
        "0, HEALTHY",
        "3000, HEALTHY", // exactly one interval: on time
        "6999, HEALTHY", // one heartbeat missed: still healthy, no flapping
        "7000, LATE",
        "14999, LATE",
        "15000, OFFLINE",
        "600000, OFFLINE"
    })
    void classify_followsTheThresholds(long silenceMillis, WorkerHealth expected) {
        assertThat(WorkerHealth.classify(NOW.minusMillis(silenceMillis), NOW, DEFAULTS))
                .isEqualTo(expected);
    }

    @Test
    void thresholdsThatWouldFlap_areRejectedAtStartup() {
        assertThatThrownBy(() -> new WorkerProperties(
                        "x".repeat(32),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(4), // less than two intervals: one late beat would flip the state
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerProperties(
                        "x".repeat(32),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(7),
                        Duration.ofSeconds(7), // offline no later than late
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerLifecycle_retiredRegistrationsAreTerminal() {
        assertThat(WorkerLifecycle.OFFLINE.canTransitionTo(WorkerLifecycle.ACTIVE))
                .isFalse();
        assertThat(WorkerLifecycle.DEREGISTERED.canTransitionTo(WorkerLifecycle.ACTIVE))
                .isFalse();
        assertThat(WorkerLifecycle.DRAINING.canTransitionTo(WorkerLifecycle.ACTIVE))
                .isFalse();
        assertThat(WorkerLifecycle.ACTIVE.canTransitionTo(WorkerLifecycle.DRAINING))
                .isTrue();
    }

    @Test
    void propertiesToString_neverRevealsTheBootstrapToken() {
        assertThat(DEFAULTS.toString()).doesNotContain("xxxx").contains("<redacted>");
    }
}
