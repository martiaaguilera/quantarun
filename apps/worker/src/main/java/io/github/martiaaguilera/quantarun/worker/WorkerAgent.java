package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptExecutor;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

/**
 * The worker's membership in the fleet and its intake of work: register, heartbeat (renewing the leases of running
 * attempts), claim assignments, re-register when retired, and leave gracefully.
 *
 * <p>{@link #step()} and {@link #claimStep()} each perform exactly one iteration and return how long to wait before
 * the next one. The loops that call them live in {@link WorkerLifecycleRunner}; keeping the decisions separate makes
 * every transition testable without threads or sleeps.
 */
public class WorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgent.class);

    record Registration(UUID workerId, String secret, Duration heartbeatInterval) {}

    private final ControlPlaneClient controlPlane;
    private final AttemptExecutor executor;
    private final WorkerSettings settings;
    private final ChaosInjector chaos;
    private final String version;
    private final Backoff backoff;
    private final RandomGenerator random;

    // Written by the membership thread, read by the claim thread and health checks.
    private volatile Registration registration;
    private volatile WorkerProtocol.WorkerLifecycleView lifecycle = WorkerProtocol.WorkerLifecycleView.ACTIVE;
    private volatile boolean leaving;
    private int consecutiveFailures;
    private int consecutiveClaimFailures;

    public WorkerAgent(
            ControlPlaneClient controlPlane,
            AttemptExecutor executor,
            WorkerSettings settings,
            ChaosInjector chaos,
            String version,
            RandomGenerator random) {
        this.controlPlane = controlPlane;
        this.executor = executor;
        this.settings = settings;
        this.chaos = chaos;
        this.version = version;
        this.backoff = new Backoff(Duration.ofMillis(500), settings.maxRetryDelay());
        this.random = random;
    }

    /** One membership iteration: register if needed, otherwise heartbeat. */
    public Duration step() {
        var current = registration;
        if (current == null) {
            // A leaving worker must not come back under a new identity halfway through its shutdown.
            return leaving ? settings.claimInterval() : register();
        }
        return heartbeat(current);
    }

    private Duration register() {
        try {
            var response = controlPlane.register(new WorkerProtocol.RegisterRequest(
                    settings.name(), version, settings.capacity().toProtocol(), settings.labels()));
            registration = new Registration(
                    response.workerId(),
                    response.workerSecret(),
                    Duration.ofMillis(response.heartbeatIntervalMillis()));
            lifecycle = WorkerProtocol.WorkerLifecycleView.ACTIVE;
            consecutiveFailures = 0;
            log.atInfo().addKeyValue("workerId", response.workerId()).log("Registered with the control plane");
            return Duration.ZERO;
        } catch (RestClientException e) {
            return failure("Registration failed", e);
        }
    }

    private Duration heartbeat(Registration current) {
        if (chaos.heartbeatPaused()) {
            // PAUSE_HEARTBEAT chaos: attempts keep running, but nothing renews their leases.
            return current.heartbeatInterval();
        }
        try {
            var response = controlPlane.heartbeat(current.secret(), executor.runningAttemptIds());
            if (response.lifecycle() != lifecycle) {
                log.atInfo().addKeyValue("lifecycle", response.lifecycle()).log("Lifecycle changed by control plane");
            }
            lifecycle = response.lifecycle();
            response.lostAttemptIds().forEach(executor::abandon);
            response.cancelAttemptIds().forEach(executor::cancel);
            chaos.apply(response.chaos());
            consecutiveFailures = 0;
            return current.heartbeatInterval();
        } catch (ControlPlaneClient.RegistrationRetiredException e) {
            // The control plane presumed this worker dead and is recovering its attempts; their results would be
            // rejected anyway. Its old identity is gone for good: stop that work and join again as new.
            log.atWarn().addKeyValue("workerId", current.workerId()).log("Registration retired; registering again");
            executor.abandonAll();
            registration = null;
            return Duration.ZERO;
        } catch (RestClientException e) {
            return failure("Heartbeat failed", e);
        }
    }

    /**
     * One intake iteration: claim as many assignments as there are free slots. A DRAINING worker still claims, because
     * the scheduler places nothing new on it, so anything assigned to it was placed before the drain. Only this
     * thread starts attempts, so the free-slot count cannot be overtaken between reading it and claiming.
     */
    public Duration claimStep() {
        var current = registration;
        var free = executor.freeSlots();
        if (current == null || leaving || free == 0 || chaos.claimingStopped()) {
            return settings.claimInterval();
        }
        try {
            var assignments = controlPlane
                    .claim(current.secret(), free, settings.claimWait())
                    .assignments();
            consecutiveClaimFailures = 0;
            for (var assignment : assignments) {
                log.atInfo()
                        .addKeyValue("attemptId", assignment.attemptId())
                        .addKeyValue("jobId", assignment.jobId())
                        .addKeyValue("attemptNo", assignment.attemptNo())
                        .addKeyValue("workloadType", assignment.workloadType())
                        .log("Attempt claimed");
                executor.start(assignment, current.secret());
            }
            // Work was waiting, so more may be: ask again at once instead of idling. An empty answer to a waiting claim
            // already spent the wait at the control plane, so the next one can start at once too.
            if (!assignments.isEmpty() || !settings.claimWait().isZero()) {
                return Duration.ZERO;
            }
            return settings.claimInterval();
        } catch (ControlPlaneClient.RegistrationRetiredException e) {
            // The membership loop notices on its next heartbeat and registers again.
            return settings.claimInterval();
        } catch (RestClientException e) {
            consecutiveClaimFailures++;
            log.atDebug().addKeyValue("error", e.getMessage()).log("Claim failed");
            return backoff.delayForFailure(consecutiveClaimFailures, random);
        }
    }

    /** The intake loop's pause: cut short when a busy worker frees a slot, since that slot can take work at once. */
    void awaitIntake(Duration delay) throws InterruptedException {
        if (executor.freeSlots() == 0) {
            executor.awaitFreeSlot(delay);
        } else {
            TimeUnit.MILLISECONDS.sleep(delay.toMillis());
        }
    }

    private Duration failure(String what, RestClientException e) {
        consecutiveFailures++;
        var delay = backoff.delayForFailure(consecutiveFailures, random);
        // The first failure is worth a warning; a long outage should not flood the log with identical lines.
        var event = consecutiveFailures == 1 || consecutiveFailures % 20 == 0 ? log.atWarn() : log.atDebug();
        event.addKeyValue("consecutiveFailures", consecutiveFailures)
                .addKeyValue("retryInMillis", delay.toMillis())
                .addKeyValue("error", e.getMessage())
                .log(what);
        return delay;
    }

    /**
     * Start of a graceful exit: claim nothing more, and tell the control plane, which drains this worker (no new
     * placements) while it still holds work, or lets it leave at once when idle. Heartbeats keep running so the
     * leases of the attempts being finished stay valid.
     */
    public void beginLeaving() {
        leaving = true;
        deregisterQuietly();
    }

    /** Waits for running attempts, then stops whatever is left so it is reported and retried elsewhere. */
    public void finishRunningAttempts(Duration grace) throws InterruptedException {
        if (!executor.awaitIdle(grace)) {
            log.atWarn()
                    .addKeyValue("stillRunning", executor.runningAttemptIds().size())
                    .log("Shutdown grace period over; stopping the remaining attempts");
            executor.stopAll();
            executor.awaitIdle(Duration.ofSeconds(5));
        }
    }

    /**
     * Final deregistration, once nothing runs. Best effort: if it fails, the control plane retires this worker after
     * the offline threshold anyway, and any lease left behind expires and is recovered.
     */
    public void deregister() {
        deregisterQuietly();
        registration = null;
    }

    private void deregisterQuietly() {
        var current = registration;
        if (current == null) {
            return;
        }
        try {
            controlPlane.deregister(current.secret());
            log.atInfo().addKeyValue("workerId", current.workerId()).log("Deregistered");
        } catch (RestClientException e) {
            // 409 once already deregistered is expected on the second call; anything else times out on its own.
            log.atDebug().addKeyValue("error", e.getMessage()).log("Deregistration not accepted");
        }
    }

    public Optional<UUID> workerId() {
        var current = registration;
        return current == null ? Optional.empty() : Optional.of(current.workerId());
    }

    public WorkerProtocol.WorkerLifecycleView lifecycle() {
        return lifecycle;
    }
}
