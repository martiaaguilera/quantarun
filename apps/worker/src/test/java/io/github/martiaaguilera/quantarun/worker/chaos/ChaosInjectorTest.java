package io.github.martiaaguilera.quantarun.worker.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosDirective;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChaosInjectorTest {

    /** Time moves only when the test says so. */
    static final class StepClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
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

    private final StepClock clock = new StepClock();
    private final AtomicInteger halts = new AtomicInteger();
    private final ChaosInjector chaos = new ChaosInjector(true, clock, halts::incrementAndGet);

    @AfterEach
    void close() {
        chaos.close();
    }

    @Test
    void aWorkerWithoutChaosEnabled_ignoresEveryFault() {
        try (var off = new ChaosInjector(false, clock, halts::incrementAndGet)) {
            off.apply(List.of(
                    directive(ChaosFault.PAUSE_HEARTBEAT, 0, 60_000, 0, 0, 0),
                    directive(ChaosFault.STALL_ATTEMPTS, 0, 0, 5, 0, 0),
                    directive(ChaosFault.PROVIDER_ERROR, 0, 0, 5, 0, 0),
                    directive(ChaosFault.KILL_WORKER, 0, 0, 0, 0, 0)));

            assertThat(off.heartbeatPaused()).isFalse();
            assertThat(off.takeStall()).isFalse();
            assertThat(off.takeProviderFault()).isEmpty();
        }
        assertThat(halts).hasValue(0);
    }

    @Test
    void timeBoxedFaults_endOnTheirOwn() {
        chaos.apply(List.of(
                directive(ChaosFault.PAUSE_HEARTBEAT, 0, 20_000, 0, 0, 0),
                directive(ChaosFault.STOP_CLAIMING, 0, 10_000, 0, 0, 0)));

        assertThat(chaos.heartbeatPaused()).isTrue();
        assertThat(chaos.claimingStopped()).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(chaos.claimingStopped()).isFalse();
        assertThat(chaos.heartbeatPaused()).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(chaos.heartbeatPaused()).isFalse();
    }

    @Test
    void countedFaults_hitExactlyTheirCount_inOrder() {
        chaos.apply(List.of(
                directive(ChaosFault.STALL_ATTEMPTS, 0, 0, 2, 0, 0),
                directive(ChaosFault.PROVIDER_RATE_LIMITED, 0, 0, 1, 4_000, 0),
                directive(ChaosFault.PROVIDER_MALFORMED, 0, 0, 1, 0, 0)));

        assertThat(chaos.takeStall()).isTrue();
        assertThat(chaos.takeStall()).isTrue();
        assertThat(chaos.takeStall()).isFalse();
        assertThat(chaos.takeProviderFault())
                .hasValue(new ChaosInjector.ProviderFault(ChaosFault.PROVIDER_RATE_LIMITED, Duration.ofSeconds(4)));
        assertThat(chaos.takeProviderFault())
                .hasValueSatisfying(fault -> assertThat(fault.fault()).isEqualTo(ChaosFault.PROVIDER_MALFORMED));
        assertThat(chaos.takeProviderFault()).isEmpty();
    }

    /** The control plane bounds every parameter; the worker bounds them again rather than trust the wire. */
    @Test
    void outOfRangeParameters_areClampedOnTheWorkerToo() {
        chaos.apply(List.of(
                directive(ChaosFault.PAUSE_HEARTBEAT, 0, Long.MAX_VALUE / 2, 0, 0, 0),
                directive(ChaosFault.STALL_ATTEMPTS, 0, 0, 1_000_000, 0, 0)));

        clock.advance(ChaosInjector.MAX_DURATION);
        assertThat(chaos.heartbeatPaused()).isFalse();
        int stalls = 0;
        while (chaos.takeStall()) {
            stalls++;
        }
        assertThat(stalls).isEqualTo(20);
    }

    @Test
    void latency_isAddedOnlyWhileTheFaultLasts() {
        chaos.apply(List.of(directive(ChaosFault.NETWORK_LATENCY, 0, 5_000, 0, 0, 50)));

        var started = System.nanoTime();
        chaos.delayCall();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(45));

        clock.advance(Duration.ofSeconds(5));
        started = System.nanoTime();
        chaos.delayCall();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(45));
    }

    /**
     * Found live: reports skipped the latency because the attempt threads carried a leftover unpark permit, and
     * {@code LockSupport.parkNanos} returns at once when one is pending.
     */
    @Test
    void latency_holdsEvenWhenTheThreadHasAPendingUnparkPermit() {
        chaos.apply(List.of(directive(ChaosFault.NETWORK_LATENCY, 0, 5_000, 0, 0, 50)));
        LockSupport.unpark(Thread.currentThread());

        var started = System.nanoTime();
        chaos.delayCall();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(45));
    }

    @Test
    void anInterruptEndsTheLatencyEarly_andStaysSet() {
        chaos.apply(List.of(directive(ChaosFault.NETWORK_LATENCY, 0, 5_000, 0, 0, 5_000)));
        Thread.currentThread().interrupt();

        var started = System.nanoTime();
        chaos.delayCall();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(Thread.interrupted()).isTrue();
    }

    private static ChaosDirective directive(
            ChaosFault fault, long delay, long duration, int count, long retryAfter, long latency) {
        return new ChaosDirective(UUID.randomUUID(), fault, delay, duration, count, retryAfter, latency);
    }
}
