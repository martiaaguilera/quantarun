package io.github.martiaaguilera.quantarun.worker.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * What the executor needs to trace and measure attempts. The tracer continues the job's trace from the context in the
 * assignment; observations cover provider calls and feed both spans and timers.
 */
public record AttemptTelemetry(
        Tracer tracer, Propagator propagator, ObservationRegistry observations, MeterRegistry meters) {

    /** For tests that do not look at telemetry. */
    public static AttemptTelemetry noop() {
        return new AttemptTelemetry(Tracer.NOOP, Propagator.NOOP, ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }
}
