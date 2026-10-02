package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/** Read model of one logical job, exactly as stored. */
public record Job(
        UUID id,
        UUID projectId,
        WorkloadType workloadType,
        ObjectNode payload,
        JobStatus status,
        int priority,
        ResourceRequest resources,
        List<String> requiredLabels,
        int maxAttempts,
        int attemptCount,
        int timeoutSeconds,
        Instant availableAt,
        @Nullable Instant deadlineAt,
        @Nullable String idempotencyKey,
        @Nullable Instant cancelRequestedAt,
        @Nullable String schedulingOutcome,
        @Nullable String schedulingReason,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant finishedAt) {}
