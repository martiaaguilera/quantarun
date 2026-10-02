package io.github.martiaaguilera.quantarun.worker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Drives the agent on two dedicated threads: membership ({@link WorkerAgent#step()}: register and heartbeat) and
 * intake ({@link WorkerAgent#claimStep()}). They are separate so a slow claim can never delay a heartbeat and let
 * leases expire.
 *
 * <p>Graceful stop, in order: stop claiming and ask the control plane to drain this worker; keep heartbeating while
 * running attempts finish (up to the grace period); stop the heartbeat loop; deregister. A worker killed without this
 * sequence is recovered by lease expiry instead, which is the point of the kill-a-worker demo.
 */
class WorkerLifecycleRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerLifecycleRunner.class);
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final WorkerAgent agent;
    private final WorkerSettings settings;
    private volatile boolean running;
    private Thread membership;
    private Thread intake;

    WorkerLifecycleRunner(WorkerAgent agent, WorkerSettings settings) {
        this.agent = agent;
        this.settings = settings;
    }

    @Override
    public void start() {
        running = true;
        membership = Thread.ofPlatform().name("worker-membership").daemon().start(() -> loop(agent::step));
        intake = Thread.ofPlatform().name("worker-intake").daemon().start(() -> loop(agent::claimStep));
    }

    private void loop(Supplier<Duration> step) {
        while (running) {
            var delay = step.get();
            try {
                TimeUnit.MILLISECONDS.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stop() {
        agent.beginLeaving();
        stopThread(intake);
        try {
            agent.finishRunningAttempts(settings.shutdownGrace());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for running attempts");
        }
        running = false;
        stopThread(membership);
        agent.deregister();
    }

    private static void stopThread(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for a worker loop to stop");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
