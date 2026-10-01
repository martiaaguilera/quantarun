package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

/**
 * The worker's membership in the fleet: register, heartbeat, re-register when retired, deregister on shutdown.
 *
 * <p>{@link #step()} performs exactly one iteration and returns how long to wait before the next one. The loop that
 * calls it lives in {@link WorkerLifecycleRunner}; keeping the decision logic separate makes every transition testable
 * without threads or sleeps.
 */
public class WorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgent.class);

    record Registration(UUID workerId, String secret, Duration heartbeatInterval) {}

    private final ControlPlaneClient controlPlane;
    private final WorkerSettings settings;
    private final String version;
    private final Backoff backoff;
    private final RandomGenerator random;

    // Written and read by the single loop thread only; volatile so health checks on other threads see the latest.
    private volatile Registration registration;
    private volatile WorkerProtocol.WorkerLifecycleView lifecycle = WorkerProtocol.WorkerLifecycleView.ACTIVE;
    private int consecutiveFailures;

    public WorkerAgent(
            ControlPlaneClient controlPlane, WorkerSettings settings, String version, RandomGenerator random) {
        this.controlPlane = controlPlane;
        this.settings = settings;
        this.version = version;
        this.backoff = new Backoff(Duration.ofMillis(500), settings.maxRetryDelay());
        this.random = random;
    }

    public Duration step() {
        var current = registration;
        return current == null ? register() : heartbeat(current);
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
        try {
            var response = controlPlane.heartbeat(current.secret(), List.of());
            if (response.lifecycle() != lifecycle) {
                log.atInfo().addKeyValue("lifecycle", response.lifecycle()).log("Lifecycle changed by control plane");
            }
            lifecycle = response.lifecycle();
            consecutiveFailures = 0;
            return current.heartbeatInterval();
        } catch (ControlPlaneClient.RegistrationRetiredException e) {
            // The control plane presumed this worker dead. Its old identity is gone for good; join again as new.
            log.atWarn().addKeyValue("workerId", current.workerId()).log("Registration retired; registering again");
            registration = null;
            return Duration.ZERO;
        } catch (RestClientException e) {
            return failure("Heartbeat failed", e);
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

    /** Best effort: if it fails, the control plane retires this worker after the offline threshold anyway. */
    public void deregister() {
        var current = registration;
        if (current == null) {
            return;
        }
        try {
            controlPlane.deregister(current.secret());
            log.atInfo().addKeyValue("workerId", current.workerId()).log("Deregistered");
        } catch (RestClientException e) {
            log.atWarn().addKeyValue("error", e.getMessage()).log("Deregistration failed; control plane will time out");
        }
        registration = null;
    }

    public Optional<UUID> workerId() {
        var current = registration;
        return current == null ? Optional.empty() : Optional.of(current.workerId());
    }

    public WorkerProtocol.WorkerLifecycleView lifecycle() {
        return lifecycle;
    }
}
