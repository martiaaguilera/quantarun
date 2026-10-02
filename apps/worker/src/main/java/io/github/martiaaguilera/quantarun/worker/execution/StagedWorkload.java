package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pipeline of stages with a checkpoint after each (docs/SPEC.md §10). Each stage's output is a digest chained from
 * the previous stage's, so the final digest proves every stage ran exactly once in order, whether or not the job was
 * interrupted. A new attempt resumes after the last committed stage instead of starting from zero.
 *
 * <p>Payload: {@code stages: [{name?, durationMs}]} (1 to 20), and optionally {@code failAtStage} with
 * {@code failOnAttempts}: attempts up to that number fail with TRANSIENT when they reach that stage, to demonstrate a
 * resume.
 */
final class StagedWorkload implements Workload {

    static final int MAX_STAGES = 20;
    static final long MAX_STAGE_DURATION_MS = 600_000;

    @Override
    public String type() {
        return "staged";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var stages = stages(payload);
        var failAtStage = payload.optionalLong("failAtStage", 0, MAX_STAGES - 1);
        var failOnAttempts = payload.optionalLong("failOnAttempts", 1, 100).orElse(1L);

        var resumeAfter =
                context.lastCheckpoint() == null ? -1 : context.lastCheckpoint().stageIndex();
        if (resumeAfter >= stages.size()) {
            // The payload is immutable, so a checkpoint beyond its stages means stored state and payload disagree.
            throw new WorkloadFailure(FailureClass.NON_RETRYABLE, "checkpoint is past the last stage");
        }
        var digest =
                resumeAfter < 0 ? "start" : digestOf(context.lastCheckpoint().result());
        for (int index = resumeAfter + 1; index < stages.size(); index++) {
            if (failAtStage.isPresent() && failAtStage.get() == index && context.attemptNo() <= failOnAttempts) {
                throw new WorkloadFailure(FailureClass.TRANSIENT, "injected failure at stage " + index);
            }
            var stage = stages.get(index);
            Thread.sleep(stage.durationMs());
            digest = sha256Hex(digest + "|" + index + "|" + stage.name());
            context.checkpoints().commit(index, Map.of("stage", index, "name", stage.name(), "digest", digest));
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("stages", stages.size());
        result.put("resumedAfterStage", resumeAfter);
        result.put("executedStages", stages.size() - resumeAfter - 1);
        result.put("digest", digest);
        return result;
    }

    private record Stage(String name, long durationMs) {}

    private static List<Stage> stages(Payload payload) {
        if (!(payload.values().get("stages") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_STAGES) {
            throw WorkloadFailure.invalidInput("'stages' must be a list of 1 to " + MAX_STAGES + " stages");
        }
        return raw.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        throw WorkloadFailure.invalidInput("each stage must be an object");
                    }
                    @SuppressWarnings("unchecked")
                    var stage = new Payload((Map<String, Object>) map);
                    return new Stage(
                            stage.optionalString("name", 64).orElse("stage"),
                            stage.requireLong("durationMs", 0, MAX_STAGE_DURATION_MS));
                })
                .toList();
    }

    private static String digestOf(Map<String, Object> checkpoint) {
        if (!(checkpoint.get("digest") instanceof String digest)) {
            throw new WorkloadFailure(FailureClass.NON_RETRYABLE, "checkpoint has no digest");
        }
        return digest;
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
