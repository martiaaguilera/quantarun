package io.github.martiaaguilera.quantarun.controlplane.projects;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tenant. {@code weight} is its relative share under fair-share scheduling.
 *
 * @param maxQueuedJobs admission quota on unfinished jobs; a submission beyond it is rejected. Null: unlimited.
 * @param maxRunningJobs scheduling quota on concurrently active attempts. Null: unlimited.
 * @param maxAccelerators scheduling quota on accelerators held by active attempts. Null: unlimited.
 */
public record Project(
        UUID id,
        String name,
        int weight,
        @Nullable Integer maxQueuedJobs,
        @Nullable Integer maxRunningJobs,
        @Nullable Integer maxAccelerators,
        Instant createdAt) {}
