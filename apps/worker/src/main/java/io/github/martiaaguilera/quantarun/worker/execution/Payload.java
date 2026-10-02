package io.github.martiaaguilera.quantarun.worker.execution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * Typed, bounded access to a workload payload. Every violation is an {@link WorkloadFailure#invalidInput}: a payload
 * that is wrong now is wrong on every retry, so it must end the job as FAILED rather than burn the retry budget.
 */
record Payload(Map<String, Object> values) {

    Payload {
        values = values == null ? Map.of() : values;
    }

    long requireLong(String field, long min, long max) {
        return optionalLong(field, min, max)
                .orElseThrow(() -> WorkloadFailure.invalidInput("'" + field + "' is required"));
    }

    Optional<Long> optionalLong(String field, long min, long max) {
        var raw = values.get(field);
        if (raw == null) {
            return Optional.empty();
        }
        var value = integral(field, raw);
        if (value < min || value > max) {
            throw WorkloadFailure.invalidInput("'" + field + "' must be between " + min + " and " + max);
        }
        return Optional.of(value);
    }

    Optional<String> optionalString(String field, int maxLength) {
        var raw = values.get(field);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String text)) {
            throw WorkloadFailure.invalidInput("'" + field + "' must be a string");
        }
        if (text.length() > maxLength) {
            throw WorkloadFailure.invalidInput("'" + field + "' must be at most " + maxLength + " characters");
        }
        return Optional.of(text);
    }

    String requireString(String field, int maxLength) {
        return optionalString(field, maxLength)
                .orElseThrow(() -> WorkloadFailure.invalidInput("'" + field + "' is required"));
    }

    private static long integral(String field, Object raw) {
        // JSON numbers arrive as Integer, Long, BigInteger or, with a fraction or exponent, as Double/BigDecimal.
        try {
            return switch (raw) {
                case Integer i -> i;
                case Long l -> l;
                case BigInteger big -> big.longValueExact();
                case BigDecimal decimal -> decimal.longValueExact();
                case Double d when d == Math.rint(d) && Math.abs(d) < 0x1p53 -> d.longValue();
                default -> throw WorkloadFailure.invalidInput("'" + field + "' must be an integer");
            };
        } catch (ArithmeticException e) {
            throw WorkloadFailure.invalidInput("'" + field + "' must be an integer within range");
        }
    }
}
