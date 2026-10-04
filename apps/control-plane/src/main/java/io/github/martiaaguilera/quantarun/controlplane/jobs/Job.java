package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read model of one logical job, exactly as stored.
 *
 * @param attemptCount every attempt the job ever had; it numbers them, so it keeps growing across revives.
 * @param budgetStart {@code attemptCount} when the current attempt budget was granted (0, or the value at the last
 *     revive). The budget left is {@code maxAttempts - (attemptCount - budgetStart)}.
 * @param traceParent W3C trace context of the submitting request; the job's whole life is recorded in that trace.
 */
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
        int budgetStart,
        int reviveCount,
        int timeoutSeconds,
        Instant availableAt,
        @Nullable Instant deadlineAt,
        @Nullable String idempotencyKey,
        @Nullable Instant cancelRequestedAt,
        @Nullable String schedulingOutcome,
        @Nullable String schedulingReason,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant finishedAt,
        @Nullable String traceParent) {

    /** Attempts used from the current budget (invariant I9 is stated over this number). */
    public int attemptsInBudget() {
        return attemptCount - budgetStart;
    }
}
