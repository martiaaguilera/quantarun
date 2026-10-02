package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** A failure with a class. The class, not the message, decides whether the control plane retries. */
final class WorkloadFailure extends RuntimeException {

    private final FailureClass failureClass;
    private final @Nullable Duration retryAfter;

    WorkloadFailure(FailureClass failureClass, String message) {
        this(failureClass, message, null);
    }

    /** @param retryAfter how long the provider asked to wait; meaningful only for RATE_LIMITED. */
    WorkloadFailure(FailureClass failureClass, String message, @Nullable Duration retryAfter) {
        super(message);
        this.failureClass = failureClass;
        this.retryAfter = retryAfter;
    }

    static WorkloadFailure invalidInput(String message) {
        return new WorkloadFailure(FailureClass.INVALID_INPUT, message);
    }

    FailureClass failureClass() {
        return failureClass;
    }

    @Nullable
    Duration retryAfter() {
        return retryAfter;
    }
}
