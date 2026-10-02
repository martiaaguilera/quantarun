package io.github.martiaaguilera.quantarun.worker.execution;

import java.util.Map;

/** Sleeps for {@code durationMs}. The simplest long-running job: the kill-a-worker demo uses it. */
final class DelayWorkload implements Workload {

    static final long MAX_DURATION_MS = 600_000;

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public Map<String, Object> execute(Payload payload, int attemptNo) throws InterruptedException {
        var duration = payload.requireLong("durationMs", 0, MAX_DURATION_MS);
        Thread.sleep(duration);
        return Map.of("sleptMs", duration);
    }
}
