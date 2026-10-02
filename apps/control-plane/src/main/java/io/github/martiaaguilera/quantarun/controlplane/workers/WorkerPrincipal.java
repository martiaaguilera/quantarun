package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Who is calling the worker protocol. The credential type, not the request path, decides what a call may do:
 * the bootstrap token can only register, and a worker credential can only act as the worker it was issued to.
 */
public sealed interface WorkerPrincipal {

    record Bootstrap() implements WorkerPrincipal {}

    record Registered(UUID workerId) implements WorkerPrincipal {}

    default void requireBootstrap() {
        if (!(this instanceof Bootstrap)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "BOOTSTRAP_TOKEN_REQUIRED", "Registration requires the bootstrap token.");
        }
    }

    default UUID requireRegisteredWorker() {
        if (this instanceof Registered registered) {
            return registered.workerId();
        }
        throw new ApiException(
                HttpStatus.FORBIDDEN,
                "WORKER_CREDENTIAL_REQUIRED",
                "This call requires a registered worker credential.");
    }
}
