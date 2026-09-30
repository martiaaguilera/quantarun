package io.github.martiaaguilera.quantarun.controlplane.jobs;

/**
 * Resources one execution of a job reserves on a worker. Accelerators are simulated slots: a count, not a device.
 */
public record ResourceRequest(int cpuMillis, int memoryMib, int accelerators) {

    public ResourceRequest {
        if (cpuMillis <= 0 || memoryMib <= 0 || accelerators < 0) {
            throw new IllegalArgumentException("Invalid resource request: " + cpuMillis + "m CPU, " + memoryMib
                    + " MiB, " + accelerators + " accelerators");
        }
    }
}
