package io.github.martiaaguilera.quantarun.controlplane.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.martiaaguilera.quantarun.controlplane.SettableClock;
import io.github.martiaaguilera.quantarun.controlplane.workers.internal.WorkerRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Regression test for the restart incident in docs/ENGINEERING_LOG.md: right after a control-plane restart every live
 * worker looks silent, because nobody recorded heartbeats while the control plane was down.
 */
class WorkerRetirementGraceTest {

    private static final Instant STARTED = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void duringStartupGrace_noWorkerIsRetired() {
        var clock = new SettableClock(STARTED);
        var repository = mock(WorkerRepository.class);
        var registry = new WorkerRegistry(repository, properties(Duration.ofSeconds(15)), clock);

        clock.set(STARTED.plusSeconds(14));

        assertThat(registry.retireSilentWorkers()).isEmpty();
        verify(repository, never()).retireSilentWorkers(any());
    }

    /**
     * The live PostgreSQL outage of 2026-10-04: while the database was down nobody's heartbeat was recorded, so when it
     * came back every live worker looked silent and was retired. A gap in hearing now earns the same grace as a restart.
     */
    @Test
    void afterAGapInHearing_retirementPausesForAGrace_thenResumes() {
        var clock = new SettableClock(STARTED);
        var repository = mock(WorkerRepository.class);
        var registry = new WorkerRegistry(repository, properties(Duration.ofSeconds(15)), clock);
        clock.set(STARTED.plusSeconds(20));
        registry.retireSilentWorkers();
        verify(repository, times(1)).retireSilentWorkers(any());

        // Ticks while the database is down throw before hearing anything.
        doThrow(new CannotGetJdbcConnectionException("down")).when(repository).ping();
        clock.set(STARTED.plusSeconds(30));
        assertThatThrownBy(registry::retireSilentWorkers).isInstanceOf(CannotGetJdbcConnectionException.class);

        doNothing().when(repository).ping();
        for (int second = 60; second < 75; second++) {
            clock.set(STARTED.plusSeconds(second));
            assertThat(registry.retireSilentWorkers()).as("second %d", second).isEmpty();
        }
        verify(repository, times(1)).retireSilentWorkers(any());

        clock.set(STARTED.plusSeconds(75));
        registry.retireSilentWorkers();
        verify(repository, times(2)).retireSilentWorkers(any());
    }

    /** A grace that the database spends down as well is no grace: the next reachable tick starts another. */
    @Test
    void anOutageDuringTheGrace_extendsIt() {
        var clock = new SettableClock(STARTED);
        var repository = mock(WorkerRepository.class);
        var registry = new WorkerRegistry(repository, properties(Duration.ofSeconds(15)), clock);
        clock.set(STARTED.plusSeconds(20));
        registry.retireSilentWorkers();
        clock.set(STARTED.plusSeconds(40));
        registry.retireSilentWorkers(); // a gap: grace until 55

        doThrow(new CannotGetJdbcConnectionException("down")).when(repository).ping();
        for (int second = 41; second <= 56; second++) {
            clock.set(STARTED.plusSeconds(second));
            assertThatThrownBy(registry::retireSilentWorkers).isInstanceOf(CannotGetJdbcConnectionException.class);
        }
        doNothing().when(repository).ping();
        clock.set(STARTED.plusSeconds(57));

        assertThat(registry.retireSilentWorkers()).isEmpty();
        verify(repository, times(1)).retireSilentWorkers(any());
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
}
