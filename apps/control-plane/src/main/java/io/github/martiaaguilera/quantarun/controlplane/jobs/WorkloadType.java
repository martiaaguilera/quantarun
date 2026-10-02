package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of built-in executors. Clients pick one of these and send a JSON payload; they can never send code or
 * commands (docs/THREAT_MODEL.md). Only types every worker can actually execute are listed: memory, http (with SSRF
 * protection) and staged (with checkpoints) arrive together with their executors in Phase 6.
 */
public enum WorkloadType {
    DELAY("delay"),
    CPU_HASH("cpu-hash"),
    MOCK_INFERENCE("mock-inference"),
    FAIL("fail");

    private final String wireName;

    WorkloadType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<WorkloadType> fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst();
    }
}
