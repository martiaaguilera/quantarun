package io.github.martiaaguilera.quantarun.worker.chaos;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosDirective;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faults this worker injects into itself when the control plane asks for them in a heartbeat response. Only the
 * predefined faults exist, each acts on this process alone, and none runs unless the operator started the worker
 * with chaos enabled. Every fault is bounded in time or in count, and the control plane bounds the parameters again.
 *
 * <p>Read from the membership, claim, attempt and HTTP client threads, so all state is volatile or atomic.
 */
public class ChaosInjector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChaosInjector.class);
    /** Upper bounds that hold even if a buggy control plane sent larger values. */
    static final Duration MAX_DURATION = Duration.ofMinutes(2);

    static final Duration MAX_DELAY = Duration.ofMinutes(1);
    static final Duration MAX_LATENCY = Duration.ofSeconds(5);
    static final int MAX_QUEUED = 100;

    /** How this process ends for KILL_WORKER. Tests substitute a recorder for {@link Runtime#halt}. */
    @FunctionalInterface
    public interface Terminator {
        void halt();
    }

    /** What the fake provider answers on the next mock-inference call. */
    public record ProviderFault(ChaosFault fault, Duration retryAfter) {}

    private final boolean enabled;
    private final Clock clock;
    private final Terminator terminator;
    private final ScheduledExecutorService killTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("chaos-kill").daemon().factory());
    private volatile Instant heartbeatPausedUntil = Instant.MIN;
    private volatile Instant claimingStoppedUntil = Instant.MIN;
    private volatile Instant latencyUntil = Instant.MIN;
    private volatile Duration latency = Duration.ZERO;
    private final AtomicInteger stalls = new AtomicInteger();
    private final ConcurrentLinkedQueue<ProviderFault> providerFaults = new ConcurrentLinkedQueue<>();

    public ChaosInjector(boolean enabled, Clock clock, Terminator terminator) {
        this.enabled = enabled;
        this.clock = clock;
        this.terminator = terminator;
    }

    public static ChaosInjector disabled() {
        return new ChaosInjector(false, Clock.systemUTC(), () -> {});
    }

    /** Halts the JVM at once: no shutdown hooks, no graceful drain, exactly like a crash. */
    public static Terminator haltProcess() {
        return () -> Runtime.getRuntime().halt(137);
    }

    public void apply(List<ChaosDirective> directives) {
        for (var directive : directives) {
            if (!enabled) {
                log.atWarn()
                        .addKeyValue("experimentId", directive.experimentId())
                        .addKeyValue("fault", directive.fault())
                        .log("Chaos fault ignored: chaos is not enabled on this worker");
                continue;
            }
            log.atWarn()
                    .addKeyValue("experimentId", directive.experimentId())
                    .addKeyValue("fault", directive.fault())
                    .log("Injecting chaos fault");
            inject(directive);
        }
    }

    private void inject(ChaosDirective directive) {
        var now = clock.instant();
        var until = now.plus(bounded(Duration.ofMillis(directive.durationMillis()), MAX_DURATION));
        var count = Math.clamp(directive.count(), 0, 20);
        switch (directive.fault()) {
            case KILL_WORKER ->
                killTimer.schedule(
                        this::kill,
                        bounded(Duration.ofMillis(directive.delayMillis()), MAX_DELAY)
                                .toMillis(),
                        TimeUnit.MILLISECONDS);
            case PAUSE_HEARTBEAT -> heartbeatPausedUntil = until;
            case STOP_CLAIMING -> claimingStoppedUntil = until;
            case NETWORK_LATENCY -> {
                latency = bounded(Duration.ofMillis(directive.latencyMillis()), MAX_LATENCY);
                latencyUntil = until;
            }
            case STALL_ATTEMPTS -> stalls.updateAndGet(current -> Math.min(MAX_QUEUED, current + count));
            case PROVIDER_RATE_LIMITED, PROVIDER_ERROR, PROVIDER_MALFORMED -> {
                var retryAfter = bounded(Duration.ofMillis(directive.retryAfterMillis()), MAX_DELAY);
                for (int i = 0; i < count && providerFaults.size() < MAX_QUEUED; i++) {
                    providerFaults.add(new ProviderFault(directive.fault(), retryAfter));
                }
            }
        }
    }

    private void kill() {
        log.atError().log("Chaos: halting this worker process now");
        terminator.halt();
    }

    public boolean heartbeatPaused() {
        return clock.instant().isBefore(heartbeatPausedUntil);
    }

    public boolean claimingStopped() {
        return clock.instant().isBefore(claimingStoppedUntil);
    }

    /**
     * Called before every request to the control plane. An interrupt (a timeout or shutdown) ends the wait early and
     * stays set for the caller to see. Not {@code LockSupport.parkNanos}: it returns at once when the thread holds a
     * leftover unpark permit, which the HTTP client's virtual threads do, so reports silently skipped the latency.
     */
    public void delayCall() {
        if (clock.instant().isBefore(latencyUntil)) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return true if the attempt about to start must hang until its timeout */
    public boolean takeStall() {
        return stalls.getAndUpdate(current -> Math.max(0, current - 1)) > 0;
    }

    public Optional<ProviderFault> takeProviderFault() {
        return Optional.ofNullable(providerFaults.poll());
    }

    @Override
    public void close() {
        killTimer.shutdownNow();
    }

    private static Duration bounded(Duration requested, Duration max) {
        return requested.isNegative() ? Duration.ZERO : (requested.compareTo(max) > 0 ? max : requested);
    }
}
