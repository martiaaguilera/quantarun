package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.web.SecretTokens;
import io.github.martiaaguilera.quantarun.controlplane.workers.internal.WorkerRepository;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The worker side of the fleet: registration, heartbeats, draining and retirement of silent workers. */
@Service
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);
    static final SecretTokens WORKER_CREDENTIALS = new SecretTokens("qw");

    private final WorkerRepository workers;
    private final WorkerProperties properties;
    private final Clock clock;
    private final Instant retirementAllowedFrom;

    WorkerRegistry(WorkerRepository workers, WorkerProperties properties, Clock clock) {
        this.workers = workers;
        this.properties = properties;
        this.clock = clock;
        this.retirementAllowedFrom = clock.instant().plus(properties.startupGrace());
    }

    /** The worker row and its first heartbeat commit together, so a registered worker is never invisible. */
    @Transactional
    public WorkerProtocol.RegisterResponse register(WorkerProtocol.RegisterRequest request) {
        var credential = WORKER_CREDENTIALS.generate();
        var workerId = workers.insert(request, credential.prefix(), credential.hash());
        log.atInfo()
                .addKeyValue("workerId", workerId)
                .addKeyValue("workerName", request.name())
                .addKeyValue("capacity", request.capacity())
                .addKeyValue("labels", request.labels())
                .log("Worker registered");
        return new WorkerProtocol.RegisterResponse(
                workerId,
                credential.plaintext(),
                properties.heartbeatInterval().toMillis(),
                properties.leaseDuration().toMillis());
    }

    /**
     * A retired registration (OFFLINE or DEREGISTERED) is answered with 409 and must register again. Accepting its
     * heartbeat would let a worker that was presumed dead resume work that may already have been recovered.
     */
    public WorkerProtocol.WorkerLifecycleView heartbeat(UUID workerId) {
        var lifecycle = requireLive(workerId);
        workers.recordHeartbeat(workerId);
        return lifecycle == WorkerLifecycle.DRAINING
                ? WorkerProtocol.WorkerLifecycleView.DRAINING
                : WorkerProtocol.WorkerLifecycleView.ACTIVE;
    }

    /**
     * Fences retired registrations out of every worker write (invariant I11): an OFFLINE worker was presumed dead and
     * its attempts are being, or were, recovered, so it may not claim, renew or report as that identity again.
     */
    public WorkerLifecycle requireLive(UUID workerId) {
        var worker = workers.findById(workerId).orElseThrow(() -> new WorkerNotFoundException(workerId));
        if (!worker.lifecycle().isLive()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "WORKER_NOT_ACTIVE",
                    "Worker " + workerId + " is " + worker.lifecycle() + "; register again.");
        }
        return worker.lifecycle();
    }

    public WorkerLifecycle deregister(UUID workerId) {
        var result = workers.deregister(workerId).orElseThrow(() -> notLive(workerId));
        log.atInfo()
                .addKeyValue("workerId", workerId)
                .addKeyValue("lifecycle", result)
                .log("Worker deregistering");
        return result;
    }

    /** Operator action: finish current work, take no new work. Draining an already draining worker is a no-op. */
    public Worker drain(UUID workerId) {
        var current = workers.findById(workerId).orElseThrow(() -> new WorkerNotFoundException(workerId));
        if (current.lifecycle() == WorkerLifecycle.ACTIVE) {
            workers.transition(workerId, List.of(WorkerLifecycle.ACTIVE), WorkerLifecycle.DRAINING);
        } else if (current.lifecycle() != WorkerLifecycle.DRAINING) {
            throw notLive(workerId);
        }
        return workers.findById(workerId).orElseThrow(() -> new WorkerNotFoundException(workerId));
    }

    public Optional<Worker> find(UUID workerId) {
        return workers.findById(workerId);
    }

    /** Registrations under {@code name} since {@code since}, oldest first: how a restarted worker shows up again. */
    public List<Worker> registrationsSince(String name, java.time.Instant since, int limit) {
        return workers.findByNameRegisteredSince(name, since, limit);
    }

    /** Called periodically by the liveness monitor. Does nothing during the startup grace period. */
    public List<WorkerRepository.RetiredWorker> retireSilentWorkers() {
        if (clock.instant().isBefore(retirementAllowedFrom)) {
            return List.of();
        }
        var retired = workers.retireSilentWorkers(properties.offlineAfter());
        retired.forEach(worker -> log.atWarn()
                .addKeyValue("workerId", worker.id())
                .addKeyValue("workerName", worker.name())
                .addKeyValue("offlineAfter", properties.offlineAfter())
                .log("Worker stopped heartbeating and was marked OFFLINE"));
        return retired;
    }

    /** Resolves a presented worker credential to the worker it belongs to, whatever its lifecycle. */
    Optional<UUID> authenticate(String presented) {
        return WORKER_CREDENTIALS
                .prefixOf(presented)
                .flatMap(workers::findCredential)
                .filter(credential -> SecretTokens.matches(presented, credential.hash()))
                .map(WorkerRepository.Credential::workerId);
    }

    private static ApiException notLive(UUID workerId) {
        return new ApiException(
                HttpStatus.CONFLICT, "WORKER_NOT_ACTIVE", "Worker " + workerId + " is no longer an active member.");
    }
}
