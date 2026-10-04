package io.github.martiaaguilera.quantarun.controlplane.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.martiaaguilera.quantarun.controlplane.chaos.FaultCatalog.Parameter;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FaultCatalogTest {

    @Test
    void everyFaultIsDefined_withItsBoundsInsideTheDatabaseChecks() {
        assertThat(FaultCatalog.definitions()).hasSize(ChaosFault.values().length);
        for (var definition : FaultCatalog.definitions()) {
            for (var range : definition.parameters()) {
                // The API must never accept what the V9 CHECK constraints would refuse.
                var dbMax = switch (range.parameter()) {
                    case DELAY_MS, RETRY_AFTER_MS -> 60_000;
                    case DURATION_MS -> 120_000;
                    case COUNT -> 20;
                    case LATENCY_MS -> 5_000;
                };
                assertThat(range.min()).isGreaterThanOrEqualTo(0);
                assertThat(range.max()).isLessThanOrEqualTo(dbMax);
                assertThat(range.defaultValue()).isBetween(range.min(), range.max());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ChaosFault.class)
    void defaults_resolveForEveryFault(ChaosFault fault) {
        var parameters = FaultCatalog.resolve(fault, Map.of());

        assertThat(parameters).isNotNull();
    }

    @Test
    void defaultPause_outlastsALeaseAndTheOfflineThreshold() {
        var pause = FaultCatalog.resolve(ChaosFault.PAUSE_HEARTBEAT, Map.of());

        assertThat(pause.durationMs()).isGreaterThan(15_000);
    }

    @Test
    void givenParameters_areUsed() {
        var parameters = FaultCatalog.resolve(
                ChaosFault.PROVIDER_RATE_LIMITED, requested(Parameter.COUNT, 3, Parameter.RETRY_AFTER_MS, 2_500));

        assertThat(parameters).isEqualTo(new FaultParameters(0, 0, 3, 2_500, 0));
    }

    @Test
    void outOfRange_isRefused() {
        assertThatThrownBy(() ->
                        FaultCatalog.resolve(ChaosFault.STALL_ATTEMPTS, requested(Parameter.COUNT, 21, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("between 1 and 20");
        assertThatThrownBy(() -> FaultCatalog.resolve(
                        ChaosFault.NETWORK_LATENCY, requested(Parameter.LATENCY_MS, 0, null, null)))
                .isInstanceOf(ApiException.class);
    }

    /** A parameter the fault does not take is a mistake in the request, not something to ignore. */
    @Test
    void aParameterTheFaultDoesNotTake_isRefused() {
        assertThatThrownBy(() -> FaultCatalog.resolve(
                        ChaosFault.KILL_WORKER, requested(Parameter.DURATION_MS, 5_000, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DURATION_MS does not apply to KILL_WORKER");
    }

    private static Map<Parameter, @Nullable Integer> requested(
            Parameter first, int value, @Nullable Parameter second, @Nullable Integer secondValue) {
        var map = new EnumMap<Parameter, @Nullable Integer>(Parameter.class);
        map.put(first, value);
        if (second != null) {
            map.put(second, secondValue);
        }
        return map;
    }
}
