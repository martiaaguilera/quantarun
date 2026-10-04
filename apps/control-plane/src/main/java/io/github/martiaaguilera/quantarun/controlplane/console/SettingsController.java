package io.github.martiaaguilera.quantarun.controlplane.console;

import io.github.martiaaguilera.quantarun.controlplane.chaos.ChaosProperties;
import io.github.martiaaguilera.quantarun.controlplane.jobs.RetryPolicy;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulerProperties;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerProperties;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configuration this control plane is running with, read-only. The console shows real settings only; changing
 * one means restarting with new configuration, which is what a reviewer of a change wants to see anyway.
 * Credentials are never part of it.
 */
@RestController
class SettingsController {

    record Scheduler(String policy, boolean loopEnabled, int loops, int windowSize, Duration idleDelay) {}

    record Workers(
            Duration heartbeatInterval,
            Duration lateAfter,
            Duration offlineAfter,
            Duration leaseDuration,
            Duration claimTimeout,
            Duration startupGrace) {}

    record Retries(Duration baseDelay, Duration maxDelay) {}

    record Chaos(boolean enabled, Duration deliveryWindow, int maxPendingPerWorker) {}

    record Settings(Scheduler scheduler, Workers workers, Retries retries, Chaos chaos) {}

    private final SchedulerProperties scheduler;
    private final WorkerProperties workers;
    private final RetryPolicy retries;
    private final ChaosProperties chaos;

    SettingsController(
            SchedulerProperties scheduler, WorkerProperties workers, RetryPolicy retries, ChaosProperties chaos) {
        this.scheduler = scheduler;
        this.workers = workers;
        this.retries = retries;
        this.chaos = chaos;
    }

    @GetMapping("/api/v1/settings")
    Settings settings(Caller caller) {
        caller.requireAdmin();
        return new Settings(
                new Scheduler(
                        scheduler.policy().name(),
                        scheduler.enabled(),
                        scheduler.loops(),
                        scheduler.windowSize(),
                        scheduler.idleDelay()),
                new Workers(
                        workers.heartbeatInterval(),
                        workers.lateAfter(),
                        workers.offlineAfter(),
                        workers.leaseDuration(),
                        workers.claimTimeout(),
                        workers.startupGrace()),
                new Retries(retries.baseDelay(), retries.maxDelay()),
                new Chaos(chaos.enabled(), chaos.deliveryWindow(), chaos.maxPendingPerWorker()));
    }
}
