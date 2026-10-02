package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

/** What one execution of a job reserves on a worker, besides the single slot every placement takes. */
public record Demand(int cpuMillis, int memoryMib, int accelerators) {}
