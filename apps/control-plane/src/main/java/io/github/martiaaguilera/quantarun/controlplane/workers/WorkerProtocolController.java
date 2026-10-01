package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(WorkerProtocol.BASE_PATH)
class WorkerProtocolController {

    record DeregisterResponse(WorkerLifecycle lifecycle) {}

    private final WorkerRegistry registry;

    WorkerProtocolController(WorkerRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    WorkerProtocol.RegisterResponse register(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @Valid @RequestBody WorkerProtocol.RegisterRequest request) {
        principal.requireBootstrap();
        return registry.register(request);
    }

    @PostMapping("/heartbeat")
    WorkerProtocol.HeartbeatResponse heartbeat(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal,
            @Valid @RequestBody WorkerProtocol.HeartbeatRequest request) {
        return registry.heartbeat(principal.requireRegisteredWorker(), request.activeAttemptIds());
    }

    @PostMapping("/deregister")
    DeregisterResponse deregister(
            @RequestAttribute(WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE) WorkerPrincipal principal) {
        return new DeregisterResponse(registry.deregister(principal.requireRegisteredWorker()));
    }
}
