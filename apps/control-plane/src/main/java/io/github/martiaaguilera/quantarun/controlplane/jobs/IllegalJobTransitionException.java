package io.github.martiaaguilera.quantarun.controlplane.jobs;

/**
 * A bug, not a client error: code attempted a transition the lifecycle forbids. It surfaces as a 500 and is
 * logged, because silently accepting it could corrupt job state.
 */
public class IllegalJobTransitionException extends IllegalStateException {

    public IllegalJobTransitionException(JobStatus from, JobStatus to) {
        super("Illegal job transition " + from + " -> " + to);
    }
}
