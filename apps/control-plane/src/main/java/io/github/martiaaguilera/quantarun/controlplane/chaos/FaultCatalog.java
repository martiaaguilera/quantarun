package io.github.martiaaguilera.quantarun.controlplane.chaos;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The closed set of faults, what each one means, and the bounded parameters it takes. Nothing outside this catalogue
 * can be injected. Defaults are chosen so that the fault visibly triggers recovery: a 30 s heartbeat pause outlasts
 * the 15 s lease and the 15 s offline threshold.
 */
public final class FaultCatalog {

    public enum Parameter {
        DELAY_MS,
        DURATION_MS,
        COUNT,
        RETRY_AFTER_MS,
        LATENCY_MS
    }

    public record ParameterRange(Parameter parameter, int min, int max, int defaultValue) {}

    public record FaultDefinition(ChaosFault fault, String description, List<ParameterRange> parameters) {}

    private static final List<FaultDefinition> DEFINITIONS = List.of(
            new FaultDefinition(
                    ChaosFault.KILL_WORKER,
                    "Halt the worker process abruptly, like a crash: its leases expire and its jobs are retried"
                            + " elsewhere.",
                    List.of(new ParameterRange(Parameter.DELAY_MS, 0, 60_000, 0))),
            new FaultDefinition(
                    ChaosFault.PAUSE_HEARTBEAT,
                    "Stop heartbeating while still executing: leases expire, late reports are fenced off, and the"
                            + " registration is retired.",
                    List.of(new ParameterRange(Parameter.DURATION_MS, 1_000, 120_000, 30_000))),
            new FaultDefinition(
                    ChaosFault.STOP_CLAIMING,
                    "Claim no assignments: the capacity disappears, and work placed on the worker waits for its"
                            + " lease to expire.",
                    List.of(new ParameterRange(Parameter.DURATION_MS, 1_000, 120_000, 30_000))),
            new FaultDefinition(
                    ChaosFault.NETWORK_LATENCY,
                    "Delay every call from the worker to the control plane.",
                    List.of(
                            new ParameterRange(Parameter.LATENCY_MS, 1, 5_000, 1_000),
                            new ParameterRange(Parameter.DURATION_MS, 1_000, 120_000, 30_000))),
            new FaultDefinition(
                    ChaosFault.STALL_ATTEMPTS,
                    "The next attempts hang until their timeout and fail with TIMEOUT.",
                    List.of(new ParameterRange(Parameter.COUNT, 1, 20, 1))),
            new FaultDefinition(
                    ChaosFault.PROVIDER_RATE_LIMITED,
                    "The next mock-inference calls get HTTP 429 with a Retry-After.",
                    List.of(
                            new ParameterRange(Parameter.COUNT, 1, 20, 1),
                            new ParameterRange(Parameter.RETRY_AFTER_MS, 0, 60_000, 5_000))),
            new FaultDefinition(
                    ChaosFault.PROVIDER_ERROR,
                    "The next mock-inference calls get HTTP 500.",
                    List.of(new ParameterRange(Parameter.COUNT, 1, 20, 1))),
            new FaultDefinition(
                    ChaosFault.PROVIDER_MALFORMED,
                    "The next mock-inference calls get a response body that cannot be parsed.",
                    List.of(new ParameterRange(Parameter.COUNT, 1, 20, 1))));

    private static final Map<ChaosFault, FaultDefinition> BY_FAULT =
            DEFINITIONS.stream().collect(Collectors.toUnmodifiableMap(FaultDefinition::fault, Function.identity()));

    static {
        if (BY_FAULT.size() != ChaosFault.values().length) {
            throw new IllegalStateException("Every fault needs a definition: " + Arrays.toString(ChaosFault.values()));
        }
    }

    private FaultCatalog() {}

    public static List<FaultDefinition> definitions() {
        return DEFINITIONS;
    }

    /**
     * Fills in defaults and checks bounds. A parameter the fault does not take is refused rather than ignored, so a
     * typo in a request cannot silently run a different experiment from the one intended.
     */
    public static FaultParameters resolve(ChaosFault fault, Map<Parameter, @Nullable Integer> requested) {
        var definition = BY_FAULT.get(fault);
        var taken = definition.parameters().stream()
                .collect(Collectors.toMap(ParameterRange::parameter, Function.identity()));
        requested.forEach((parameter, value) -> {
            if (value != null && !taken.containsKey(parameter)) {
                throw invalid(parameter + " does not apply to " + fault + ".");
            }
        });
        var values = new int[Parameter.values().length];
        for (var range : definition.parameters()) {
            var value = requested.get(range.parameter());
            var resolved = value == null ? range.defaultValue() : value;
            if (resolved < range.min() || resolved > range.max()) {
                throw invalid(range.parameter() + " for " + fault + " must be between " + range.min() + " and "
                        + range.max() + ".");
            }
            values[range.parameter().ordinal()] = resolved;
        }
        return new FaultParameters(
                values[Parameter.DELAY_MS.ordinal()],
                values[Parameter.DURATION_MS.ordinal()],
                values[Parameter.COUNT.ordinal()],
                values[Parameter.RETRY_AFTER_MS.ordinal()],
                values[Parameter.LATENCY_MS.ordinal()]);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FAULT_PARAMETERS", detail);
    }
}
