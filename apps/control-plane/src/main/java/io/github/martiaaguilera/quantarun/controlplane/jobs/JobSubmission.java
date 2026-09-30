package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A validated submission with every default applied. The idempotency fingerprint is computed from this
 * normalised form, so omitting a field and sending its default value count as the same request.
 */
public record JobSubmission(
        WorkloadType workloadType,
        ObjectNode payload,
        int priority,
        ResourceRequest resources,
        List<String> requiredLabels,
        int maxAttempts,
        int timeoutSeconds,
        @Nullable Instant notBefore,
        @Nullable Instant deadline) {

    public static final int DEFAULT_PRIORITY = 4;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    public JobSubmission {
        // Sorted and de-duplicated: label order carries no meaning and must not change the fingerprint.
        requiredLabels = requiredLabels.stream().distinct().sorted().toList();
    }
}
