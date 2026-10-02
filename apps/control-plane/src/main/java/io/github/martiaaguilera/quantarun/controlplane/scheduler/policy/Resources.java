package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

/**
 * An amount of every schedulable resource. One placement always consumes one slot in addition to its CPU, memory and
 * accelerators, so a worker's slot count bounds its concurrency regardless of how small the jobs are.
 */
public record Resources(int cpuMillis, int memoryMib, int accelerators, int slots) {

    public boolean covers(Demand demand) {
        return cpuMillis >= demand.cpuMillis()
                && memoryMib >= demand.memoryMib()
                && accelerators >= demand.accelerators()
                && slots >= 1;
    }

    public Resources minus(Demand demand) {
        return new Resources(
                cpuMillis - demand.cpuMillis(),
                memoryMib - demand.memoryMib(),
                accelerators - demand.accelerators(),
                slots - 1);
    }

    /** Describes the first resource that does not cover {@code demand}, for explanations. */
    String firstShortfall(Demand demand, String available) {
        if (cpuMillis < demand.cpuMillis()) {
            return "needs " + demand.cpuMillis() + "m CPU, " + cpuMillis + "m " + available;
        }
        if (memoryMib < demand.memoryMib()) {
            return "needs " + demand.memoryMib() + " MiB, " + memoryMib + " MiB " + available;
        }
        if (accelerators < demand.accelerators()) {
            return "needs " + demand.accelerators() + " accelerators, " + accelerators + " " + available;
        }
        return "needs a slot, " + slots + " " + available;
    }
}
