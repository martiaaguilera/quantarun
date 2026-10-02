package io.github.martiaaguilera.quantarun.worker.execution;

import java.util.Map;

/**
 * A built-in executor. Clients choose one by name and send a bounded JSON payload; they never send code.
 *
 * <p>Implementations must react to interruption promptly: it is how a timeout, a cancel request or a lost lease
 * stops them. Blocking calls ({@code Thread.sleep}) do so on their own; CPU loops must check
 * {@link Thread#interrupted()}.
 */
interface Workload {

    /** Wire name, as in the job's {@code workloadType}. */
    String type();

    /**
     * @param attemptNo the 1-based attempt number, so a workload can behave differently on a retry (the {@code fail}
     *     workload uses it to demonstrate recovery).
     * @return the result reported to the control plane; small, since it is stored with the attempt.
     * @throws WorkloadFailure for a classified failure, which drives the control plane's retry decision.
     */
    Map<String, Object> execute(Payload payload, int attemptNo) throws InterruptedException;
}
