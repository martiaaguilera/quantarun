package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Fails on purpose with a chosen class, to demonstrate retries, backoff and the retry budget. With
 * {@code succeedOnAttempt: n} it fails every attempt before the n-th and then succeeds, which shows a transient
 * fault healing on retry. {@code retryAfterMillis} plays a provider's Retry-After on a RATE_LIMITED failure.
 */
final class FailWorkload implements Workload {

    @Override
    public String type() {
        return "fail";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) {
        var failureClass = failureClass(payload.requireString("failureClass", 64));
        var message = payload.optionalString("message", 500).orElse("injected failure");
        var succeedOnAttempt = payload.optionalLong("succeedOnAttempt", 1, 100);
        var retryAfter = payload.optionalLong("retryAfterMillis", 0, 3_600_000).map(Duration::ofMillis);
        if (succeedOnAttempt.isPresent() && context.attemptNo() >= succeedOnAttempt.get()) {
            return Map.of("succeededOnAttempt", context.attemptNo());
        }
        throw new WorkloadFailure(failureClass, message, retryAfter.orElse(null));
    }

    private static FailureClass failureClass(String name) {
        FailureClass parsed;
        try {
            parsed = FailureClass.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw WorkloadFailure.invalidInput("unknown failureClass '" + name + "'");
        }
        // Only the control plane decides that a worker was lost; a workload cannot claim it.
        if (parsed == FailureClass.WORKER_LOST) {
            throw WorkloadFailure.invalidInput("failureClass WORKER_LOST cannot be injected");
        }
        return parsed;
    }
}
