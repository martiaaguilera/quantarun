package io.github.martiaaguilera.quantarun.controlplane.chaos;

/** The resolved parameters of one fault; unused ones are 0. Bounds are checked by {@link FaultCatalog}. */
public record FaultParameters(int delayMs, int durationMs, int count, int retryAfterMs, int latencyMs) {}
