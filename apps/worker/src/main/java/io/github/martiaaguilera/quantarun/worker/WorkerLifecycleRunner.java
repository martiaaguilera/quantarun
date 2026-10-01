package io.github.martiaaguilera.quantarun.worker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Drives {@link WorkerAgent#step()} on one dedicated thread from application start until shutdown. Stopping
 * interrupts the wait, joins the thread, and then deregisters, so the control plane learns about a graceful exit
 * immediately instead of after the offline threshold.
 */
class WorkerLifecycleRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerLifecycleRunner.class);
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final WorkerAgent agent;
    private volatile boolean running;
    private Thread loop;

    WorkerLifecycleRunner(WorkerAgent agent) {
        this.agent = agent;
    }

    @Override
    public void start() {
        running = true;
        loop = Thread.ofPlatform().name("worker-membership").daemon().start(this::runLoop);
    }

    private void runLoop() {
        while (running) {
            var delay = agent.step();
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
        running = false;
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(JOIN_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for the membership loop to stop");
            }
        }
        agent.deregister();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
