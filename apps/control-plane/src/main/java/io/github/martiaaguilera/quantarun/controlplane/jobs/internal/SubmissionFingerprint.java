package io.github.martiaaguilera.quantarun.controlplane.jobs.internal;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 over a canonical JSON rendering of the normalised submission. Canonical means that object keys are
 * sorted at every depth, so {"a":1,"b":2} and {"b":2,"a":1} fingerprint identically. This mapper is deliberately
 * independent of the application's configured mapper: a global serialization setting must never change the
 * fingerprints of already-stored requests.
 */
public final class SubmissionFingerprint {

    private static final JsonMapper CANONICAL =
            JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();

    private SubmissionFingerprint() {}

    public static byte[] of(JobSubmission submission) {
        var canonicalTree = CANONICAL.valueToTree(JsonSubmission.from(submission));
        return sha256(CANONICAL.writeValueAsString(canonicalTree));
    }

    /** Field-for-field mirror of {@link JobSubmission} with a stable, explicitly named JSON shape. */
    private record JsonSubmission(
            String workloadType,
            Object payload,
            int priority,
            int cpuMillis,
            int memoryMib,
            int accelerators,
            List<String> requiredLabels,
            int maxAttempts,
            int timeoutSeconds,
            String notBefore,
            String deadline) {

        static JsonSubmission from(JobSubmission submission) {
            return new JsonSubmission(
                    submission.workloadType().wireName(),
                    submission.payload(),
                    submission.priority(),
                    submission.resources().cpuMillis(),
                    submission.resources().memoryMib(),
                    submission.resources().accelerators(),
                    submission.requiredLabels(),
                    submission.maxAttempts(),
                    submission.timeoutSeconds(),
                    submission.notBefore() == null ? "" : submission.notBefore().toString(),
                    submission.deadline() == null ? "" : submission.deadline().toString());
        }
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
