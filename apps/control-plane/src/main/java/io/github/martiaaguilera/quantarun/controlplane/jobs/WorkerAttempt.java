package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An attempt seen from the worker that held it. */
public record WorkerAttempt(
        UUID attemptId,
        UUID jobId,
        int attemptNo,
        AttemptStatus status,
        Instant assignedAt,
        @Nullable Instant finishedAt,
        @Nullable String failureClass) {}
