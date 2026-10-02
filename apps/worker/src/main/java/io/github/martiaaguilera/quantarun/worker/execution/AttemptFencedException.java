package io.github.martiaaguilera.quantarun.worker.execution;

/**
 * The control plane refused a write about this attempt (404/409): it was recovered, or this registration retired.
 * The work must stop and must not be reported; the control plane has already decided what happens to the job.
 */
final class AttemptFencedException extends RuntimeException {

    AttemptFencedException(String message) {
        super(message);
    }
}
