package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import io.github.martiaaguilera.quantarun.worker.Backoff;
import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Runs claimed attempts, at most one per execution slot, and reports their outcomes.
 *
 * <p>Every attempt ends exactly one way. Whoever first sets its stop reason decides: the attempt finishing on its own,
 * its timeout, a cancel request, a lost lease or a shutdown. Only the winner of that race may interrupt the
 * execution thread, so a late timeout can never interrupt the thread while it is already reporting a success.
 *
 * <p>An attempt stays in {@link #runningAttemptIds()} until its report is done, so heartbeats keep renewing its lease
 * while a report is being retried.
 */
public class AttemptExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AttemptExecutor.class);
    /** The protocol's cap on retryAfterMillis; a longer Retry-After is reported as this. */
    private static final long MAX_RETRY_AFTER_MILLIS = 3_600_000;

    /** How an attempt ended, from the worker's point of view. */
    enum Stop {
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
        /** The control plane says this worker no longer owns the attempt: stop and never report. */
        LOST,
        SHUTDOWN
    }

    /**
     * @param attempts how many times a report is sent before giving up; the lease then expires and the control plane
     *     recovers the attempt, so a lost report costs a retry, never correctness.
     */
    public record ReportPolicy(int attempts, Duration baseDelay, Duration maxDelay) {}

    /**
     * @param allowedPrivateAddresses IPs or CIDRs the http workload may reach although they are internal; empty in
     *     production, so only public addresses are reachable.
     */
    public record HttpSettings(List<String> allowedPrivateAddresses, Duration connectTimeout) {}

    private static final class Running {
        final WorkerProtocol.Assignment assignment;
        final String credential;
        final AtomicReference<@Nullable Stop> stop = new AtomicReference<>();
        volatile @Nullable Thread thread;

        Running(WorkerProtocol.Assignment assignment, String credential) {
            this.assignment = assignment;
            this.credential = credential;
        }
    }

    private final ControlPlaneClient controlPlane;
    private final ChaosInjector chaos;
    private final AttemptTelemetry telemetry;
    private final int slots;
    private final ReportPolicy reportPolicy;
    private final RandomGenerator random;
    private final Map<String, Workload> workloads;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("attempt-", 0).factory());
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("attempt-timeouts").daemon().factory());
    private final Object idle = new Object();

    public AttemptExecutor(
            ControlPlaneClient controlPlane,
            int slots,
            ReportPolicy reportPolicy,
            HttpSettings http,
            ChaosInjector chaos,
            AttemptTelemetry telemetry,
            RandomGenerator random) {
        this.controlPlane = controlPlane;
        this.chaos = chaos;
        this.telemetry = telemetry;
        this.slots = slots;
        this.reportPolicy = reportPolicy;
        this.random = random;
        var providerCalls = new ProviderCalls(telemetry.observations());
        this.workloads = Stream.of(
                        new DelayWorkload(),
                        new CpuHashWorkload(),
                        new MockInferenceWorkload(chaos, providerCalls),
                        new FailWorkload(),
                        new MemoryWorkload(),
                        new StagedWorkload(),
                        new HttpWorkload(
                                http.allowedPrivateAddresses(),
                                http.connectTimeout(),
                                Clock.systemUTC(),
                                providerCalls))
                .collect(Collectors.toUnmodifiableMap(Workload::type, Function.identity()));
        Gauge.builder("quantarun.worker.slots.busy", running, Map::size)
                .description("Execution slots running an attempt")
                .register(telemetry.meters());
        Gauge.builder("quantarun.worker.slots", () -> slots)
                .description("Execution slots this worker offers")
                .register(telemetry.meters());
    }

    /** Only the claiming thread adds attempts, so this never under-counts what it is about to claim. */
    public int freeSlots() {
        return Math.max(0, slots - running.size());
    }

    public List<UUID> runningAttemptIds() {
        return List.copyOf(running.keySet());
    }

    /**
     * @param credential the registration that claimed it; reports go out under that identity even if the worker has
     *     registered again since, in which case the control plane rejects them, as it should.
     */
    public void start(WorkerProtocol.Assignment assignment, String credential) {
        var attempt = new Running(assignment, credential);
        if (running.putIfAbsent(assignment.attemptId(), attempt) != null) {
            return;
        }
        threads.execute(() -> run(attempt));
    }

    /** The job was cancelled: stop and report CANCELLED. */
    public void cancel(UUID attemptId) {
        var attempt = running.get(attemptId);
        if (attempt != null) {
            stop(attempt, Stop.CANCELLED);
        }
    }

    /** The lease is gone and the attempt was (or is being) recovered: stop and stay silent. */
    public void abandon(UUID attemptId) {
        var attempt = running.get(attemptId);
        if (attempt != null && attempt.stop.getAndSet(Stop.LOST) == null) {
            interrupt(attempt);
        }
    }

    /** This registration was retired, so nothing it runs belongs to it any more. */
    public void abandonAll() {
        running.keySet().forEach(this::abandon);
    }

    /** Shutdown ran out of patience: stop what is left and report it as a transient failure, to be retried elsewhere. */
    public void stopAll() {
        running.values().forEach(attempt -> stop(attempt, Stop.SHUTDOWN));
    }

    /** @return true if nothing is running any more */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (!running.isEmpty()) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(idle, remaining);
            }
            return true;
        }
    }

    /**
     * While every slot is busy, waits until one frees or {@code timeout} passes. The intake loop used to sleep its whole
     * claim interval here, so each finished attempt left its slot empty for up to 500 ms (BENCHMARKS.md). The check
     * and the wait share the monitor that a finishing attempt notifies after freeing its slot, so no wake-up is lost.
     */
    public void awaitFreeSlot(Duration timeout) throws InterruptedException {
        synchronized (idle) {
            if (freeSlots() == 0) {
                TimeUnit.NANOSECONDS.timedWait(idle, timeout.toNanos());
            }
        }
    }

    @Override
    public void close() {
        timeouts.shutdownNow();
        threads.shutdownNow();
    }

    /**
     * The attempt runs inside a span that continues the job's trace from the assignment, so its execution, provider
     * calls and the report (the HTTP client propagates the context) join the trace that began at submission.
     */
    private void run(Running attempt) {
        var assignment = attempt.assignment;
        attempt.thread = Thread.currentThread();
        var span = attemptSpan(assignment);
        var started = System.nanoTime();
        ScheduledFuture<?> timeout =
                timeouts.schedule(() -> stop(attempt, Stop.TIMED_OUT), assignment.timeoutSeconds(), TimeUnit.SECONDS);
        try (var _ = telemetry.tracer().withSpan(span)) {
            var report = execute(attempt);
            timeout.cancel(false);
            // Any interrupt meant for the execution is irrelevant now; it must not abort the report's HTTP call.
            Thread.interrupted();
            if (report != null) {
                send(attempt, report);
            }
        } finally {
            timeout.cancel(false);
            var stop = attempt.stop.get();
            var stopName = stop == null ? "UNKNOWN" : stop.name();
            span.tag("quantarun.stop", stopName);
            span.end();
            Timer.builder("quantarun.worker.attempts")
                    .description("Attempts run by this worker, from claim to the end of the report")
                    .tag("workload_type", assignment.workloadType())
                    .tag("stop", stopName)
                    .register(telemetry.meters())
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            running.remove(assignment.attemptId());
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    private Span attemptSpan(WorkerProtocol.Assignment assignment) {
        var builder = assignment.traceParent() == null
                ? telemetry.tracer().spanBuilder().setNoParent()
                : telemetry
                        .propagator()
                        .extract(Map.of("traceparent", assignment.traceParent()), (carrier, key) -> carrier.get(key));
        return builder.name("attempt.run")
                .tag("quantarun.attempt.id", assignment.attemptId().toString())
                .tag("quantarun.job.id", assignment.jobId().toString())
                .tag("quantarun.attempt.no", String.valueOf(assignment.attemptNo()))
                .tag("quantarun.workload_type", assignment.workloadType())
                .start();
    }

    /** @return the report to send, or null when the attempt must not be reported (it was lost). */
    private WorkerProtocol.@Nullable ReportRequest execute(Running attempt) {
        var assignment = attempt.assignment;
        Map<String, Object> result = null;
        RuntimeException failure = null;
        boolean interrupted = false;
        // Stopped before it even started (cancelled or lost while queued): skip straight to the outcome.
        if (attempt.stop.get() == null) {
            var context = new AttemptContext(
                    assignment.attemptNo(),
                    assignment.lastCheckpoint(),
                    (stage, stageResult) -> commitCheckpoint(attempt, stage, stageResult));
            try {
                if (chaos.takeStall()) {
                    // STALL_ATTEMPTS chaos: hang until the attempt's own timeout interrupts this thread.
                    new CountDownLatch(1).await();
                }
                result = workload(assignment.workloadType()).execute(new Payload(assignment.payload()), context);
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (AttemptFencedException e) {
                // The control plane refused a checkpoint: the attempt is no longer ours. Stop as if it were lost.
                attempt.stop.compareAndSet(null, Stop.LOST);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        var stoppedBy = attempt.stop.compareAndExchange(null, Stop.COMPLETED);
        if (stoppedBy == null) {
            return completed(assignment, result, failure, interrupted);
        }
        log.atInfo()
                .addKeyValue("attemptId", assignment.attemptId())
                .addKeyValue("jobId", assignment.jobId())
                .addKeyValue("stop", stoppedBy)
                .log("Attempt stopped");
        return switch (stoppedBy) {
            case LOST -> null;
            case CANCELLED -> new WorkerProtocol.ReportRequest(AttemptOutcome.CANCELLED, null, "cancelled", null, null);
            case TIMED_OUT ->
                failed(FailureClass.TIMEOUT, "exceeded the attempt timeout of " + assignment.timeoutSeconds() + " s");
            case SHUTDOWN -> failed(FailureClass.TRANSIENT, "worker shut down before the attempt finished");
            case COMPLETED -> throw new IllegalStateException("COMPLETED is only set by this method");
        };
    }

    private static WorkerProtocol.ReportRequest completed(
            WorkerProtocol.Assignment assignment,
            @Nullable Map<String, Object> result,
            @Nullable RuntimeException failure,
            boolean interrupted) {
        if (failure instanceof WorkloadFailure classified) {
            var retryAfter = classified.failureClass() == FailureClass.RATE_LIMITED ? classified.retryAfter() : null;
            return new WorkerProtocol.ReportRequest(
                    AttemptOutcome.FAILED,
                    classified.failureClass(),
                    bounded(classified.getMessage()),
                    null,
                    retryAfter == null ? null : cappedMillis(retryAfter));
        }
        if (failure != null) {
            log.atWarn()
                    .addKeyValue("attemptId", assignment.attemptId())
                    .setCause(failure)
                    .log("Workload failed unexpectedly");
            return failed(FailureClass.INTERNAL, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        if (interrupted) {
            // Nobody asked it to stop, yet it was interrupted: say so rather than invent a success.
            return failed(FailureClass.INTERNAL, "interrupted without a stop request");
        }
        return new WorkerProtocol.ReportRequest(AttemptOutcome.SUCCEEDED, null, null, result, null);
    }

    /** Compared as a Duration first: a target's Retry-After can exceed what {@code toMillis} can represent. */
    private static long cappedMillis(Duration retryAfter) {
        var cap = Duration.ofMillis(MAX_RETRY_AFTER_MILLIS);
        return retryAfter.compareTo(cap) > 0 ? MAX_RETRY_AFTER_MILLIS : retryAfter.toMillis();
    }

    private Workload workload(String type) {
        var workload = workloads.get(type);
        if (workload == null) {
            // A control plane newer than this worker: retrying on the same build cannot help.
            throw new WorkloadFailure(FailureClass.NON_RETRYABLE, "this worker cannot execute '" + type + "'");
        }
        return workload;
    }

    private static WorkerProtocol.ReportRequest failed(FailureClass failureClass, @Nullable String message) {
        return new WorkerProtocol.ReportRequest(AttemptOutcome.FAILED, failureClass, bounded(message), null, null);
    }

    private static @Nullable String bounded(@Nullable String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /**
     * Commits one stage, with the same bounded retries as a report. A rejection means the attempt was recovered: the
     * workload must stop and stay silent. Running out of retries fails the attempt as TRANSIENT; the retry resumes
     * from the last stage that did commit, so nothing committed is lost.
     */
    private void commitCheckpoint(Running attempt, int stageIndex, Map<String, Object> result)
            throws InterruptedException {
        var attemptId = attempt.assignment.attemptId();
        var backoff = new Backoff(reportPolicy.baseDelay(), reportPolicy.maxDelay());
        var faults = 0;
        for (int tryNo = 1; ; tryNo++) {
            try {
                controlPlane.checkpoint(
                        attempt.credential, attemptId, new WorkerProtocol.CheckpointRequest(stageIndex, result));
                return;
            } catch (ControlPlaneClient.ReportRejectedException e) {
                throw new AttemptFencedException("checkpoint for stage " + stageIndex + " rejected: " + e.getMessage());
            } catch (HttpClientErrorException e) {
                throw new WorkloadFailure(
                        FailureClass.NON_RETRYABLE,
                        "checkpoint for stage " + stageIndex + " refused with HTTP "
                                + e.getStatusCode().value());
            } catch (RestClientException e) {
                if (!controlPlaneUnavailable(e) && ++faults >= reportPolicy.attempts()) {
                    throw new WorkloadFailure(
                            FailureClass.TRANSIENT, "could not commit the checkpoint for stage " + stageIndex);
                }
                Thread.sleep(backoff.delayForFailure(tryNo, random));
            }
        }
    }

    /**
     * Sends the report until the control plane answers. A rejection (404/409) is final: the attempt was recovered or
     * this registration retired, and the control plane already decided what happens to the job. An unreachable control
     * plane or a 503 says nothing about the report, so it never uses up the retry budget: the attempt stays in the
     * heartbeats meanwhile, and only the control plane can end the wait, by accepting the report or by calling the
     * attempt lost. Giving up there turned a 25 s database outage into a retry of every finished attempt
     * (ENGINEERING_LOG, 2026-10-04). Other server errors may be deterministic, so they use up the budget.
     */
    private void send(Running attempt, WorkerProtocol.ReportRequest report) {
        var attemptId = attempt.assignment.attemptId();
        var backoff = new Backoff(reportPolicy.baseDelay(), reportPolicy.maxDelay());
        var faults = 0;
        for (int tryNo = 1; ; tryNo++) {
            if (attempt.stop.get() == Stop.LOST) {
                return;
            }
            try {
                var response = controlPlane.report(attempt.credential, attemptId, report);
                log.atInfo()
                        .addKeyValue("attemptId", attemptId)
                        .addKeyValue("jobId", attempt.assignment.jobId())
                        .addKeyValue("outcome", report.outcome())
                        .addKeyValue("jobStatus", response == null ? null : response.jobStatus())
                        .log("Attempt reported");
                return;
            } catch (ControlPlaneClient.ReportRejectedException e) {
                log.atInfo()
                        .addKeyValue("attemptId", attemptId)
                        .addKeyValue("reason", e.getMessage())
                        .log("Report rejected; the attempt was already resolved by the control plane");
                return;
            } catch (HttpClientErrorException e) {
                log.atError()
                        .addKeyValue("attemptId", attemptId)
                        .addKeyValue("status", e.getStatusCode().value())
                        .log("Report refused as invalid; not retrying");
                return;
            } catch (RestClientException e) {
                if (!controlPlaneUnavailable(e) && ++faults >= reportPolicy.attempts()) {
                    log.atWarn()
                            .addKeyValue("attemptId", attemptId)
                            .addKeyValue("error", e.getMessage())
                            .log("Giving up on the report; the lease will expire and the attempt will be recovered");
                    return;
                }
                try {
                    Thread.sleep(backoff.delayForFailure(tryNo, random));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The control plane could not be reached, or said it cannot serve right now (503): nothing about the request. */
    private static boolean controlPlaneUnavailable(RestClientException e) {
        return e instanceof ResourceAccessException
                || (e instanceof HttpServerErrorException server
                        && server.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    private void stop(Running attempt, Stop reason) {
        if (attempt.stop.compareAndSet(null, reason)) {
            interrupt(attempt);
        }
    }

    private static void interrupt(Running attempt) {
        var thread = attempt.thread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
