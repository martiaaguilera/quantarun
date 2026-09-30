package io.github.martiaaguilera.quantarun.controlplane.projects;

import java.time.Instant;
import java.util.UUID;

/** A tenant. {@code weight} is its relative share under fair-share scheduling. */
public record Project(UUID id, String name, int weight, Instant createdAt) {}
