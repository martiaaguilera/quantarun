package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;

/** A failure with a class. The class, not the message, decides whether the control plane retries. */
final class WorkloadFailure extends RuntimeException {

    private final FailureClass failureClass;

    WorkloadFailure(FailureClass failureClass, String message) {
        super(message);
        this.failureClass = failureClass;
    }

    static WorkloadFailure invalidInput(String message) {
        return new WorkloadFailure(FailureClass.INVALID_INPUT, message);
    }

    FailureClass failureClass() {
        return failureClass;
    }
}
