package io.github.martiaaguilera.quantarun.controlplane.workers;

/** An amount of each schedulable resource: used both for capacity and for what is currently reserved. */
public record WorkerResources(int cpuMillis, int memoryMib, int accelerators, int slots) {}
