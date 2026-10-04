package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.jobs.RetryPolicy;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Demand;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementPlanner;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Resources;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.JobOutcome;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.TraceJob;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A discrete-event simulation of the control plane: arrivals, scheduling cycles, attempts finishing or failing, worker
 * crashes and lease expiry, retries. Simulated time only; no clock, no threads, no sleeping.
 *
 * <p>The decisions come from the production code: {@link PlacementPlanner} places jobs over a window selected the way
 * the live cycle selects it, and {@link RetryPolicy} decides retries. What the simulator does not model is the
 * database: lock contention, latency and the heartbeat machinery are measured separately (benchmarks).
 *
 * <p>Determinism: event ties break on a sequence number, every random draw comes from one seeded generator, and the
 * planner's own ties break on ids. The only non-deterministic output is the measured planning time, which is kept out
 * of the result hash.
 */
final class Simulator {

    /** The live defaults: the lease after which a crashed worker's attempts are recovered, and the cycle window. */
    static final long LEASE_MS = 15_000;

    static final int WINDOW = 200;

    /** Bounds the cycles run at one instant, like the live loop that repeats only while it places work. */
    private static final int MAX_CYCLES_PER_INSTANT = 50;

    private static final RetryPolicy RETRY_POLICY = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));

    private final Trace trace;
    private final SchedulingPolicy policy;
    private final SplittableRandom random;

    private final PriorityQueue<Event> events =
            new PriorityQueue<>(Comparator.comparingLong(Event::timeMs).thenComparingLong(Event::sequence));
    private long sequence;
    private long now;

    private final Map<UUID, JobState> jobs = new LinkedHashMap<>();
    private final Map<UUID, WorkerState> workers = new LinkedHashMap<>();
    private final Map<UUID, Integer> weights = new HashMap<>();
    private final Map<UUID, Double> virtualTimes = new HashMap<>();
    private double systemVirtualTime;
    private final TreeSet<JobState> runnable;
    private final Map<UUID, TreeSet<JobState>> runnableByProject = new HashMap<>();
    private final Map<UUID, Attempt> attempts = new HashMap<>();
    /** Dominant share of the fleet held by each project's attempts that are doing useful work (for fairness). */
    private final Map<UUID, Double> servingShare = new HashMap<>();
    /** Dominant share of the fleet each project's waiting jobs would need (its unmet demand, for fairness). */
    private final Map<UUID, Double> waitingShare = new HashMap<>();

    private final Metrics metrics;

    Simulator(Trace trace, SchedulingPolicy policy, long seed) {
        this.trace = trace;
        this.policy = policy;
        this.random = new SplittableRandom(seed);
        this.runnable = new TreeSet<>(windowOrder(policy));
        trace.projects().forEach(project -> {
            weights.put(project.id(), project.weight());
            virtualTimes.put(project.id(), 0.0);
        });
        trace.workers().forEach(worker -> workers.put(worker.id(), new WorkerState(worker)));
        this.metrics = new Metrics(trace);
    }

    SimulationResult.PolicyResult run() {
        trace.jobs().forEach(job -> schedule(job.arrivalMs(), EventType.ARRIVAL, job.id(), null));
        trace.outages().forEach(outage -> {
            schedule(outage.downAtMs(), EventType.WORKER_DOWN, outage.workerId(), null);
            schedule(outage.upAtMs(), EventType.WORKER_UP, outage.workerId(), null);
        });

        while (!events.isEmpty()) {
            var at = events.peek().timeMs();
            metrics.advance(at, this);
            now = at;
            while (!events.isEmpty() && events.peek().timeMs() == at) {
                handle(events.poll());
            }
            scheduleCycles();
        }
        return metrics.result(
                policy, jobs.values().stream().map(JobState::outcome).toList(), now);
    }

    private void handle(Event event) {
        switch (event.type()) {
            case ARRIVAL -> {
                var trace = jobByIdInTrace(event.subject());
                var job = new JobState(trace);
                jobs.put(trace.id(), job);
                makeRunnable(job);
            }
            case RETRY_READY -> {
                var job = jobs.get(event.subject());
                if (job.status == Status.RETRY_WAIT) {
                    makeRunnable(job);
                }
            }
            case FINISH -> {
                var attempt = attempts.get(event.attempt());
                if (attempt != null && !attempt.lost) {
                    finish(attempt);
                }
            }
            case WORKER_DOWN -> {
                var worker = workers.get(event.subject());
                worker.up = false;
                // Nobody notices yet: the attempts keep their reservations until their leases expire.
                // Sorted, so the order of the lease events never depends on HashMap iteration order.
                var running = attempts.values().stream()
                        .sorted(Comparator.comparing(attempt -> attempt.id))
                        .toList();
                for (var attempt : running) {
                    if (attempt.workerId.equals(worker.id()) && !attempt.lost) {
                        attempt.lost = true;
                        stopServing(attempt);
                        schedule(now + LEASE_MS, EventType.LEASE_EXPIRED, attempt.job.trace.id(), attempt.id);
                    }
                }
            }
            case WORKER_UP -> workers.get(event.subject()).up = true;
            case LEASE_EXPIRED -> {
                var attempt = attempts.remove(event.attempt());
                if (attempt != null) {
                    release(attempt);
                    fail(attempt.job, FailureClass.WORKER_LOST, null);
                }
            }
        }
    }

    /** Runs cycles at this instant while they place work, like the live loop. */
    private void scheduleCycles() {
        for (int cycle = 0; cycle < MAX_CYCLES_PER_INSTANT && !runnable.isEmpty(); cycle++) {
            if (runCycle() == 0) {
                return;
            }
        }
    }

    private int runCycle() {
        var window = window();
        var liveWorkers = workers.values().stream()
                .filter(worker -> worker.up)
                .map(WorkerState::candidate)
                .toList();
        if (liveWorkers.isEmpty()) {
            return 0;
        }
        var projectStates = new HashMap<UUID, SchedulingSnapshot.ProjectState>();
        for (var job : window) {
            var projectId = job.trace.projectId();
            projectStates.computeIfAbsent(
                    projectId,
                    id -> new SchedulingSnapshot.ProjectState(
                            id,
                            id.toString(),
                            weights.getOrDefault(id, 1),
                            virtualTimes.getOrDefault(id, 0.0),
                            null,
                            null,
                            0,
                            0));
        }
        var snapshot = new SchedulingSnapshot(
                Instant.EPOCH.plusMillis(now),
                window.stream().map(JobState::pending).toList(),
                liveWorkers,
                projectStates,
                systemVirtualTime);

        var started = System.nanoTime();
        var plan = PlacementPlanner.plan(snapshot, policy);
        metrics.planningNanos(System.nanoTime() - started);

        virtualTimes.putAll(plan.virtualTimes());
        systemVirtualTime = plan.systemVirtualTime();
        var placed = 0;
        for (var decision : plan.decisions()) {
            if (decision.outcome() == PlacementDecision.Outcome.PLACED) {
                start(jobs.get(decision.job().id()), workers.get(decision.chosenWorkerId()));
                placed++;
            }
        }
        return placed;
    }

    /** The jobs a live cycle would lock: the same orderings and the same round-robin for FAIR_SHARE. */
    private List<JobState> window() {
        if (policy != SchedulingPolicy.FAIR_SHARE) {
            var window = new ArrayList<JobState>(WINDOW);
            for (var job : runnable) {
                window.add(job);
                if (window.size() == WINDOW) {
                    break;
                }
            }
            return window;
        }
        var window = new ArrayList<JobState>(WINDOW);
        var iterators = runnableByProject.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().iterator())
                .toList();
        boolean progressed = true;
        while (window.size() < WINDOW && progressed) {
            progressed = false;
            for (var iterator : iterators) {
                if (iterator.hasNext() && window.size() < WINDOW) {
                    window.add(iterator.next());
                    progressed = true;
                }
            }
        }
        return window;
    }

    private void start(JobState job, WorkerState worker) {
        removeRunnable(job);
        job.status = Status.RUNNING;
        job.attemptNo++;
        job.attemptsInBudget++;
        if (job.firstStartMs == null) {
            job.firstStartMs = now;
        }
        worker.reserve(job.trace.demand());
        var attempt = new Attempt(new UUID(job.trace.id().getLeastSignificantBits(), job.attemptNo), job, worker.id());
        attempts.put(attempt.id, attempt);
        servingShare.merge(job.trace.projectId(), metrics.dominantShare(job.trace.demand()), Double::sum);
        var failure = job.trace.failures().get(job.attemptNo);
        attempt.failure = failure;
        var runFor = failure == null ? job.trace.durationMs() : Math.min(failure.afterMs(), job.trace.durationMs());
        schedule(now + runFor, EventType.FINISH, job.trace.id(), attempt.id);
        metrics.started(job, now);
    }

    private void finish(Attempt attempt) {
        attempts.remove(attempt.id);
        stopServing(attempt);
        release(attempt);
        if (attempt.failure == null) {
            attempt.job.status = Status.SUCCEEDED;
            attempt.job.finishedMs = now;
            metrics.completed(attempt.job, now);
            return;
        }
        var retryAfter =
                attempt.failure.retryAfterMs() == null ? null : Duration.ofMillis(attempt.failure.retryAfterMs());
        fail(attempt.job, attempt.failure.failureClass(), retryAfter);
    }

    private void fail(JobState job, FailureClass failureClass, @Nullable Duration retryAfter) {
        var decision = RETRY_POLICY.decide(
                failureClass, job.attemptsInBudget, job.trace.maxAttempts(), false, retryAfter, random);
        switch (decision) {
            case RetryPolicy.Decision.Retry(var delay) -> {
                job.status = Status.RETRY_WAIT;
                job.availableAtMs = now + delay.toMillis();
                if (delay.isZero()) {
                    makeRunnable(job);
                } else {
                    schedule(job.availableAtMs, EventType.RETRY_READY, job.trace.id(), null);
                }
            }
            case RetryPolicy.Decision.GiveUp(var terminal, var reason) -> {
                job.status = terminal == io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.DEAD
                        ? Status.DEAD
                        : Status.FAILED;
                job.finishedMs = now;
            }
        }
    }

    private void stopServing(Attempt attempt) {
        servingShare.merge(
                attempt.job.trace.projectId(), -metrics.dominantShare(attempt.job.trace.demand()), Double::sum);
    }

    private void release(Attempt attempt) {
        workers.get(attempt.workerId).release(attempt.job.trace.demand());
    }

    private void makeRunnable(JobState job) {
        job.status = job.status == Status.RETRY_WAIT ? Status.RETRY_WAIT : Status.QUEUED;
        job.runnable = true;
        runnable.add(job);
        waitingShare.merge(job.trace.projectId(), metrics.dominantShare(job.trace.demand()), Double::sum);
        runnableByProject
                .computeIfAbsent(job.trace.projectId(), id -> new TreeSet<>(windowOrder(SchedulingPolicy.FIFO)))
                .add(job);
    }

    private void removeRunnable(JobState job) {
        job.runnable = false;
        waitingShare.merge(job.trace.projectId(), -metrics.dominantShare(job.trace.demand()), Double::sum);
        runnable.remove(job);
        var byProject = runnableByProject.get(job.trace.projectId());
        if (byProject != null) {
            byProject.remove(job);
        }
    }

    private TraceJob jobByIdInTrace(UUID id) {
        return traceIndex().get(id);
    }

    private @Nullable Map<UUID, TraceJob> index;

    private Map<UUID, TraceJob> traceIndex() {
        if (index == null) {
            index = new HashMap<>();
            trace.jobs().forEach(job -> index.put(job.id(), job));
        }
        return index;
    }

    private void schedule(long atMs, EventType type, UUID subject, @Nullable UUID attempt) {
        events.add(new Event(atMs, sequence++, type, subject, attempt));
    }

    /** The order of the live window for each policy, ending on the id so it is total. */
    private static Comparator<JobState> windowOrder(SchedulingPolicy policy) {
        Comparator<JobState> oldest =
                Comparator.<JobState>comparingLong(job -> job.availableAtMs).thenComparing(job -> job.trace.id());
        return switch (policy.ordering()) {
            case PRIORITY ->
                Comparator.<JobState>comparingInt(job -> -job.trace.priority()).thenComparing(oldest);
            case EARLIEST_DEADLINE ->
                Comparator.<JobState, Long>comparing(
                                job -> job.trace.deadlineMs(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(job -> -job.trace.priority())
                        .thenComparing(oldest);
            case FIFO, FAIR_SHARE -> oldest;
        };
    }

    enum EventType {
        ARRIVAL,
        FINISH,
        RETRY_READY,
        WORKER_DOWN,
        WORKER_UP,
        LEASE_EXPIRED
    }

    private record Event(
            long timeMs,
            long sequence,
            EventType type,
            UUID subject,
            @Nullable UUID attempt) {}

    enum Status {
        QUEUED,
        RUNNING,
        RETRY_WAIT,
        SUCCEEDED,
        FAILED,
        DEAD
    }

    static final class JobState {
        final TraceJob trace;
        Status status = Status.QUEUED;
        long availableAtMs;
        int attemptNo;
        int attemptsInBudget;
        boolean runnable;

        @Nullable
        Long firstStartMs;

        @Nullable
        Long finishedMs;

        JobState(TraceJob trace) {
            this.trace = trace;
            this.availableAtMs = trace.arrivalMs();
        }

        SchedulingSnapshot.PendingJob pending() {
            return new SchedulingSnapshot.PendingJob(
                    trace.id(),
                    trace.projectId(),
                    trace.priority(),
                    Instant.EPOCH.plusMillis(availableAtMs),
                    trace.deadlineMs() == null ? null : Instant.EPOCH.plusMillis(trace.deadlineMs()),
                    trace.demand(),
                    trace.requiredLabels());
        }

        JobOutcome outcome() {
            return new JobOutcome(trace.id(), status.name(), attemptNo, firstStartMs, finishedMs);
        }
    }

    static final class WorkerState {
        final Trace.TraceWorker worker;
        Resources free;
        boolean up = true;

        WorkerState(Trace.TraceWorker worker) {
            this.worker = worker;
            this.free = worker.capacity();
        }

        UUID id() {
            return worker.id();
        }

        /** The planner never overcommits (I1); if it ever did, the simulation must fail rather than report nonsense. */
        void reserve(Demand demand) {
            if (!free.covers(demand)) {
                throw new IllegalStateException("Overcommit on " + worker.name() + ": " + demand + " > " + free);
            }
            free = free.minus(demand);
        }

        void release(Demand demand) {
            free = new Resources(
                    free.cpuMillis() + demand.cpuMillis(),
                    free.memoryMib() + demand.memoryMib(),
                    free.accelerators() + demand.accelerators(),
                    free.slots() + 1);
        }

        SchedulingSnapshot.WorkerCandidate candidate() {
            return new SchedulingSnapshot.WorkerCandidate(
                    worker.id(), worker.name(), worker.labels(), worker.capacity(), free, true, null);
        }
    }

    private static final class Attempt {
        final UUID id;
        final JobState job;
        final UUID workerId;
        boolean lost;
        Trace.@Nullable Failure failure;

        Attempt(UUID id, JobState job, UUID workerId) {
            this.id = id;
            this.job = job;
            this.workerId = workerId;
        }
    }

    Map<UUID, WorkerState> workers() {
        return workers;
    }

    Map<UUID, JobState> jobs() {
        return jobs;
    }

    Map<UUID, Double> waitingShareByProject() {
        return waitingShare;
    }

    Map<UUID, Double> servingShareByProject() {
        return servingShare;
    }
}
