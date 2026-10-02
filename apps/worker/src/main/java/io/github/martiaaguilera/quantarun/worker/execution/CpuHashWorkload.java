package io.github.martiaaguilera.quantarun.worker.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Real CPU work: a chain of {@code iterations} SHA-256 hashes, each over the previous digest. Deterministic, so a
 * retried attempt reports the same digest, and its cost grows linearly with the iteration count.
 */
final class CpuHashWorkload implements Workload {

    static final long MAX_ITERATIONS = 100_000_000;
    /** How often the loop checks for interruption: rare enough to be free, frequent enough to stop within a millisecond. */
    private static final int INTERRUPT_CHECK_MASK = 4095;

    @Override
    public String type() {
        return "cpu-hash";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var iterations = payload.requireLong("iterations", 1, MAX_ITERATIONS);
        var seed = payload.optionalString("seed", 256).orElse("quantarun");
        var sha256 = sha256();
        var digest = seed.getBytes(StandardCharsets.UTF_8);
        for (long i = 0; i < iterations; i++) {
            if ((i & INTERRUPT_CHECK_MASK) == 0 && Thread.interrupted()) {
                throw new InterruptedException("cpu-hash interrupted after " + i + " iterations");
            }
            digest = sha256.digest(digest);
        }
        return Map.of("iterations", iterations, "sha256", HexFormat.of().formatHex(digest));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
