package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

public record JobEvent(
        long id, UUID jobId, @Nullable UUID attemptId, JobEventType type, Instant occurredAt, ObjectNode details) {}
