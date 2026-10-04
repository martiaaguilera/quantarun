package io.github.martiaaguilera.quantarun.controlplane.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.martiaaguilera.quantarun.controlplane.workers.internal.WorkerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the restart incident in docs/ENGINEERING_LOG.md: right after a control-plane restart every live
 * worker looks silent, because nobody recorded heartbeats while the control plane was down.
 */
class WorkerRetirementGraceTest {

    private static final Instant STARTED = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void duringStartupGrace_noWorkerIsRetiredAndTheDatabaseIsNotEvenAsked() {
        var clock = new SettableClock(STARTED);
        var repository = mock(WorkerRepository.class);
        var registry = new WorkerRegistry(repository, properties(Duration.ofSeconds(15)), clock);

        clock.set(STARTED.plusSeconds(14));

        assertThat(registry.retireSilentWorkers()).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void afterStartupGrace_silentWorkersAreRetiredAsUsual() {
        var clock = new SettableClock(STARTED);
        var repository = mock(WorkerRepository.class);
        var silent = new WorkerRepository.RetiredWorker(UUID.randomUUID(), "silent");
        when(repository.retireSilentWorkers(any())).thenReturn(List.of(silent));
        var registry = new WorkerRegistry(repository, properties(Duration.ofSeconds(15)), clock);

        clock.set(STARTED.plusSeconds(15));

        assertThat(registry.retireSilentWorkers()).containsExactly(silent);
        verify(repository).retireSilentWorkers(Duration.ofSeconds(15));
    }

    private static WorkerProperties properties(Duration startupGrace) {
        return new WorkerProperties(
                "x".repeat(32),
                Duration.ofSeconds(3),
                Duration.ofSeconds(7),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                Duration.ofSeconds(1),
                startupGrace,
                Duration.ofSeconds(30));
    }

    private static final class SettableClock extends Clock {
        private volatile Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
