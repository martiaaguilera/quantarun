package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class JobNotFoundException extends ApiException {

    public JobNotFoundException(UUID jobId) {
        super(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job " + jobId + " not found.");
    }
}
