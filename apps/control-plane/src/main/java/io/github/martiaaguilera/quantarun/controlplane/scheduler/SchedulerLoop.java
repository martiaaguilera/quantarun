package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import io.github.martiaaguilera.quantarun.controlplane.jobs.PlacementSignal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;

/**
 * Runs scheduling cycles continuously on a fixed number of threads. A cycle that placed work runs again at once so a
 * backlog drains quickly; an idle cycle waits {@code idleDelay}, which bounds the polling load on an empty queue, or
 * less when {@link PlacementSignal} reports a submission or freed capacity committed in this process.
 */
class SchedulerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);
    private static final Duration FAILURE_DELAY = Duration.ofSeconds(1);

    private final SchedulingCycle cycle;
    private final SchedulerProperties properties;
    private final MeterRegistry meters;
    private final PlacementSignal signal;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    SchedulerLoop(SchedulingCycle cycle, SchedulerProperties properties, MeterRegistry meters, PlacementSignal signal) {
        this.cycle = cycle;
        this.properties = properties;
        this.meters = meters;
        this.signal = signal;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < properties.loops(); i++) {
            threads.add(Thread.ofPlatform().name("scheduler-" + i).daemon().start(this::loop));
        }
        log.atInfo()
                .addKeyValue("policy", properties.policy())
                .addKeyValue("loops", properties.loops())
                .log("Scheduler started");
    }

    private void loop() {
        while (running) {
            Duration pause;
            var idle = false;
            try {
                // Timed here, around the transactional call, so the duration includes the commit.
                var started = System.nanoTime();
                var result = cycle.runCycle(properties.policy());
                recordCycle(System.nanoTime() - started, result.placed());
                pause = result.placed() > 0 ? Duration.ZERO : properties.idleDelay();
                idle = result.placed() == 0;
            } catch (DataAccessException e) {
                // A failed cycle rolls back as a whole, leaving no partial placement; the next cycle simply retries.
                log.atWarn().addKeyValue("error", e.getMessage()).log("Scheduling cycle failed; retrying");
                pause = FAILURE_DELAY;
            } catch (RuntimeException e) {
                // This loop supervises the cycle, so it must outlive any bug in it: a dead scheduler thread fails
                // silently and stops all placement. The bug is still surfaced at ERROR with its stack trace.
                log.error("Unexpected failure in scheduling cycle; retrying", e);
                pause = FAILURE_DELAY;
            }
            try {
                if (idle) {
                    signal.await(pause);
                } else {
                    TimeUnit.MILLISECONDS.sleep(pause.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void recordCycle(long nanos, int placed) {
        var policy = properties.policy().name();
        Timer.builder("quantarun.scheduler.cycle")
                .description("One scheduling cycle: lock a window, plan, place, commit")
                .tag("policy", policy)
                .tag("result", placed > 0 ? "placed" : "idle")
                .publishPercentileHistogram()
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        if (placed > 0) {
            Counter.builder("quantarun.scheduler.placements")
                    .description("Attempts placed on workers")
                    .tag("policy", policy)
                    .register(meters)
                    .increment(placed);
        }
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (var thread : threads) {
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
