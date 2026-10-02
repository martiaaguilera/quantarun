package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of built-in executors. Clients pick one of these and send a JSON payload; they can never send code or
 * commands (docs/SPEC.md §13). Only types every worker can actually execute are listed.
 */
public enum WorkloadType {
    DELAY("delay"),
    CPU_HASH("cpu-hash"),
    MOCK_INFERENCE("mock-inference"),
    FAIL("fail"),
    MEMORY("memory"),
    /** Calls an external HTTP endpoint; the worker blocks private and loopback targets after DNS resolution. */
    HTTP("http"),
    /** A sequence of stages with a checkpoint after each; a retry resumes after the last committed stage. */
    STAGED("staged");

    private final String wireName;

    WorkloadType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Only workloads designed for it commit checkpoints (docs/SPEC.md §10). */
    public boolean isCheckpointable() {
        return this == STAGED;
    }

    public static Optional<WorkloadType> fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst();
    }
}
