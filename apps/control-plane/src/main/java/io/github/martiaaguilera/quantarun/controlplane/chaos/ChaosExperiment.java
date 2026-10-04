package io.github.martiaaguilera.quantarun.controlplane.chaos;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** @param jobId set when the experiment was aimed at the worker running this job. */
public record ChaosExperiment(
        UUID id,
        ChaosFault fault,
        UUID workerId,
        @Nullable UUID jobId,
        FaultParameters parameters,
        ChaosStatus status,
        Instant createdAt,
        Instant deliverBy,
        @Nullable Instant deliveredAt,
        @Nullable Instant endedAt) {

    WorkerProtocol.ChaosDirective toDirective() {
        return new WorkerProtocol.ChaosDirective(
                id,
                fault,
                parameters.delayMs(),
                parameters.durationMs(),
                parameters.count(),
                parameters.retryAfterMs(),
                parameters.latencyMs());
    }
}
