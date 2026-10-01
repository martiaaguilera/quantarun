package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class WorkerNotFoundException extends ApiException {

    public WorkerNotFoundException(UUID workerId) {
        super(HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND", "Worker " + workerId + " not found.");
    }
}
