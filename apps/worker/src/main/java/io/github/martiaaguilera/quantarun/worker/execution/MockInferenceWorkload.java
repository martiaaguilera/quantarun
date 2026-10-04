package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Stands in for an LLM call without any model, network or API key: it waits {@code latencyMs}, "generates"
 * {@code outputTokens} token ids from a seeded random source, and reports token counts the way a provider would.
 * The same payload always yields the same output digest, so a retry is observably the same request.
 */
final class MockInferenceWorkload implements Workload {

    static final long MAX_INPUT_TOKENS = 1_000_000;
    static final long MAX_OUTPUT_TOKENS = 100_000;
    static final long MAX_LATENCY_MS = 600_000;
    private static final int VOCABULARY_SIZE = 50_000;
    private static final int PREVIEW_TOKENS = 8;

    private final ChaosInjector chaos;

    MockInferenceWorkload(ChaosInjector chaos) {
        this.chaos = chaos;
    }

    @Override
    public String type() {
        return "mock-inference";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var inputTokens = payload.requireLong("inputTokens", 1, MAX_INPUT_TOKENS);
        var outputTokens = payload.requireLong("outputTokens", 1, MAX_OUTPUT_TOKENS);
        var latencyMs = payload.requireLong("latencyMs", 0, MAX_LATENCY_MS);
        var seed = payload.optionalLong("seed", Long.MIN_VALUE, Long.MAX_VALUE).orElse(0L);

        Thread.sleep(latencyMs);
        chaos.takeProviderFault().ifPresent(fault -> {
            throw providerFailure(fault);
        });

        // The prompt size feeds the seed, so two requests that differ only in input size answer differently.
        var random = new SplittableRandom(seed ^ (inputTokens * 0x9E3779B97F4A7C15L));
        var digest = sha256();
        var preview = new ArrayList<Integer>(PREVIEW_TOKENS);
        var buffer = ByteBuffer.allocate(Integer.BYTES);
        for (long i = 0; i < outputTokens; i++) {
            var token = random.nextInt(VOCABULARY_SIZE);
            if (preview.size() < PREVIEW_TOKENS) {
                preview.add(token);
            }
            digest.update(buffer.clear().putInt(token).array());
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("model", "mock");
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("totalTokens", inputTokens + outputTokens);
        result.put("latencyMs", latencyMs);
        result.put("outputDigest", HexFormat.of().formatHex(digest.digest()));
        result.put("previewTokenIds", preview);
        return result;
    }

    /**
     * Classified the way {@link HttpWorkload} classifies a real provider: 429 is RATE_LIMITED with its Retry-After, and
     * 500 is TRANSIENT. A body that cannot be parsed is TRANSIENT too: providers send them when a response is cut off or
     * a proxy answers in their place, and the same request usually succeeds when sent again.
     */
    private static WorkloadFailure providerFailure(ChaosInjector.ProviderFault fault) {
        return switch (fault.fault()) {
            case PROVIDER_RATE_LIMITED ->
                new WorkloadFailure(
                        FailureClass.RATE_LIMITED, "provider answered HTTP 429 (chaos)", fault.retryAfter());
            case PROVIDER_ERROR -> new WorkloadFailure(FailureClass.TRANSIENT, "provider answered HTTP 500 (chaos)");
            case PROVIDER_MALFORMED ->
                new WorkloadFailure(FailureClass.TRANSIENT, "provider response could not be parsed (chaos)");
            default -> throw new IllegalArgumentException("Not a provider fault: " + fault.fault());
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
