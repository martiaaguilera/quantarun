package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Joins everything that happens to a job into the trace of the request that submitted it. The submission's W3C
 * {@code traceparent} is stored with the job; placement adds a span for the time spent queued and one for the
 * scheduling decision, and the decision's context is handed to the worker with the assignment, so execution and the
 * final report land in the same trace.
 *
 * <p>The job's trace outlives the request that started it: placement happens seconds or hours later, in another
 * thread, so its spans are created against the stored context, not the current one.
 */
@Component
class JobTracing {

    static final String TRACE_PARENT = "traceparent";
    private static final Pattern W3C = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final int MAX_TAG_LENGTH = 200;

    private final Tracer tracer;
    private final Propagator propagator;

    JobTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The context of the span in progress (the submitting request), to store with a new job. */
    @Nullable
    String currentTraceParent() {
        var span = tracer.currentSpan();
        return span == null ? null : traceParentOf(span);
    }

    /**
     * Records a placement in the job's trace and returns the context its execution belongs to. The spans end only if
     * the placement commits; a rolled-back placement leaves no trace. The queued span runs from when the job became
     * runnable ({@code availableAt}: its submission, or the end of its retry backoff) to this placement.
     */
    @Nullable
    String placement(Job job, int attemptNo, UUID workerId, String reason) {
        var now = Instant.now();
        var queued = childOf(job.traceParent())
                .name("job.queued")
                .tag("quantarun.job.id", job.id().toString())
                .tag("quantarun.attempt.no", String.valueOf(attemptNo))
                .startTimestamp(job.availableAt().toEpochMilli(), TimeUnit.MILLISECONDS)
                .start();
        var schedule = childOf(job.traceParent())
                .name("job.schedule")
                .tag("quantarun.job.id", job.id().toString())
                .tag("quantarun.attempt.no", String.valueOf(attemptNo))
                .tag("quantarun.worker.id", workerId.toString())
                .tag("quantarun.decision", truncate(reason))
                .start();
        AfterCommit.run(
                () -> {
                    queued.end(now.toEpochMilli(), TimeUnit.MILLISECONDS);
                    schedule.end();
                },
                () -> {
                    queued.abandon();
                    schedule.abandon();
                });
        return traceParentOf(schedule);
    }

    /** The control plane gave up on an attempt whose lease expired: a span in the job's trace marks the moment. */
    void attemptLost(@Nullable String attemptTraceParent, UUID attemptId, UUID workerId) {
        var span = childOf(attemptTraceParent)
                .name("attempt.lost")
                .tag("quantarun.attempt.id", attemptId.toString())
                .tag("quantarun.worker.id", workerId.toString())
                .tag("quantarun.reason", "lease expired")
                .start();
        AfterCommit.run(span::end, span::abandon);
    }

    /** Only well-formed W3C contexts are stored; anything else (a malformed client header) starts no trace link. */
    static @Nullable String validOrNull(@Nullable String traceParent) {
        return traceParent != null && W3C.matcher(traceParent).matches() ? traceParent : null;
    }

    private Span.Builder childOf(@Nullable String traceParent) {
        var parent = validOrNull(traceParent);
        if (parent == null) {
            return tracer.spanBuilder().setNoParent();
        }
        return propagator.extract(Map.of(TRACE_PARENT, parent), (carrier, key) -> carrier.get(key));
    }

    private @Nullable String traceParentOf(Span span) {
        var headers = new HashMap<String, String>();
        propagator.inject(span.context(), headers, (carrier, key, value) -> carrier.put(key, value));
        return validOrNull(headers.get(TRACE_PARENT));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TAG_LENGTH ? value : value.substring(0, MAX_TAG_LENGTH);
    }
}
