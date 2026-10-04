package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.Distribution;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.JobOutcome;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.PlanningTime;
import io.github.martiaaguilera.quantarun.controlplane.simulation.SimulationResult.Utilisation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Collects a simulation's metrics as simulated time advances. Utilisation and fairness are time integrals: between two
 * events nothing changes, so each step adds (state) × (time since the last event).
 */
final class Metrics {

    private final Trace trace;
    private final Map<UUID, Integer> weights = new HashMap<>();
    private final long totalCpu;
    private final long totalMemory;
    private final long totalAccelerators;
    private final long totalSlots;

    private long lastMs;
    private double cpuIntegral;
    private double memoryIntegral;
    private double acceleratorIntegral;
    private double slotIntegral;
    /**
     * Per project, integrated over the time when at least two projects wanted capacity: the service received
     * (dominant share × ms) and the service it was entitled to under weighted max-min fairness (same unit).
     */
    private final Map<UUID, double[]> service = new HashMap<>();

    private final List<Long> queueWaits = new ArrayList<>();
    private final Map<UUID, List<Long>> queueWaitsByProject = new HashMap<>();
    private final List<Long> latencies = new ArrayList<>();
    private int attempts;
    private final List<Long> planningNanos = new ArrayList<>();

    Metrics(Trace trace) {
        this.trace = trace;
        trace.projects().forEach(project -> weights.put(project.id(), project.weight()));
        totalCpu = trace.workers().stream()
                .mapToLong(w -> w.capacity().cpuMillis())
                .sum();
        totalMemory = trace.workers().stream()
                .mapToLong(w -> w.capacity().memoryMib())
                .sum();
        totalAccelerators = trace.workers().stream()
                .mapToLong(w -> w.capacity().accelerators())
                .sum();
        totalSlots =
                trace.workers().stream().mapToLong(w -> w.capacity().slots()).sum();
    }

    void advance(long toMs, Simulator simulator) {
        var dt = toMs - lastMs;
        if (dt <= 0) {
            return;
        }
        for (var worker : simulator.workers().values()) {
            var capacity = worker.worker.capacity();
            cpuIntegral += (double) (capacity.cpuMillis() - worker.free.cpuMillis()) * dt;
            memoryIntegral += (double) (capacity.memoryMib() - worker.free.memoryMib()) * dt;
            acceleratorIntegral += (double) (capacity.accelerators() - worker.free.accelerators()) * dt;
            slotIntegral += (double) (capacity.slots() - worker.free.slots()) * dt;
        }
        accumulateFairness(simulator, dt);
        lastMs = toMs;
    }

    void started(Simulator.JobState job, long nowMs) {
        attempts++;
        if (job.attemptNo == 1) {
            queueWaits.add(nowMs - job.trace.arrivalMs());
            queueWaitsByProject
                    .computeIfAbsent(job.trace.projectId(), id -> new ArrayList<>())
                    .add(nowMs - job.trace.arrivalMs());
        }
    }

    void completed(Simulator.JobState job, long nowMs) {
        latencies.add(nowMs - job.trace.arrivalMs());
    }

    void planningNanos(long nanos) {
        planningNanos.add(nanos);
    }

    SimulationResult.PolicyResult result(SchedulingPolicy policy, List<JobOutcome> outcomes, long endMs) {
        int succeeded = 0;
        int failed = 0;
        int dead = 0;
        int unfinished = 0;
        int withDeadline = 0;
        int missed = 0;
        long starvation = 0;
        var byId = new HashMap<UUID, JobOutcome>();
        outcomes.forEach(outcome -> byId.put(outcome.jobId(), outcome));
        for (var job : trace.jobs()) {
            var outcome = byId.get(job.id());
            var status = outcome == null ? "QUEUED" : outcome.status();
            switch (status) {
                case "SUCCEEDED" -> succeeded++;
                case "FAILED" -> failed++;
                case "DEAD" -> dead++;
                default -> unfinished++;
            }
            var firstStart = outcome == null ? null : outcome.firstStartMs();
            var waited = (firstStart == null ? endMs : firstStart) - job.arrivalMs();
            starvation = Math.max(starvation, waited);
            if (job.deadlineMs() != null) {
                withDeadline++;
                var onTime = "SUCCEEDED".equals(status)
                        && outcome.finishedMs() != null
                        && outcome.finishedMs() <= job.deadlineMs();
                if (!onTime) {
                    missed++;
                }
            }
        }
        var makespan = endMs
                - trace.jobs().stream()
                        .mapToLong(Trace.TraceJob::arrivalMs)
                        .min()
                        .orElse(0);
        var span = Math.max(1, endMs);
        var metrics = new SimulationResult.Metrics(
                succeeded,
                failed,
                dead,
                unfinished,
                attempts,
                makespan,
                makespan == 0 ? 0 : succeeded * 60_000.0 / makespan,
                distribution(queueWaits),
                distribution(latencies),
                withDeadline == 0 ? null : missed / (double) withDeadline,
                starvation,
                new Utilisation(
                        cpuIntegral / (totalCpu * (double) span),
                        memoryIntegral / (totalMemory * (double) span),
                        totalAccelerators == 0 ? null : acceleratorIntegral / (totalAccelerators * (double) span),
                        slotIntegral / (totalSlots * (double) span)),
                jainsIndex(),
                planningNanos.size(),
                trace.projects().stream()
                        .map(project -> new SimulationResult.ProjectWait(
                                project.name(),
                                project.weight(),
                                distribution(queueWaitsByProject.getOrDefault(project.id(), List.of()))))
                        .toList());
        return new SimulationResult.PolicyResult(policy, metrics, planningTime(), hash(metrics, outcomes));
    }

    /**
     * Weighted max-min fairness, the standard notion behind fair queuing. At each instant every project with work
     * (waiting or running) is entitled to a share of the fleet found by water-filling: capacity is split in proportion
     * to weight, and a project that wants less than its split gets what it wants while the rest is split again among
     * the others. A project that is not asking for more than it gets is never counted as wronged. Only instants with at
     * least two contending projects count; without contention there is nothing to be fair about.
     *
     * <p>An earlier version compared service rates only while a project had jobs waiting. It scored FIFO fairer than
     * FAIR_SHARE on NOISY_NEIGHBOR: under fair share a quiet tenant's job waits only a moment, often with nothing of its
     * own running, which that metric read as starvation (docs/ENGINEERING_LOG.md).
     */
    private void accumulateFairness(Simulator simulator, long dt) {
        var demand = new TreeMap<UUID, Double>();
        simulator.waitingShareByProject().forEach((project, share) -> {
            if (share > 1e-12) {
                demand.merge(project, share, Double::sum);
            }
        });
        simulator.servingShareByProject().forEach((project, share) -> {
            if (share > 1e-12) {
                demand.merge(project, share, Double::sum);
            }
        });
        if (demand.size() < 2) {
            return;
        }
        var entitled = waterFill(demand);
        for (var project : demand.keySet()) {
            var totals = service.computeIfAbsent(project, id -> new double[2]);
            totals[0] += Math.max(0, simulator.servingShareByProject().getOrDefault(project, 0.0)) * dt;
            totals[1] += entitled.get(project) * dt;
        }
    }

    /** Splits a capacity of 1 (the whole fleet) by weight, never giving a project more than it demands. */
    private Map<UUID, Double> waterFill(TreeMap<UUID, Double> demand) {
        var entitled = new HashMap<UUID, Double>();
        var open = new TreeMap<>(demand);
        var capacity = 1.0;
        while (!open.isEmpty() && capacity > 1e-12) {
            var totalWeight = open.keySet().stream()
                    .mapToDouble(id -> weights.getOrDefault(id, 1))
                    .sum();
            var level = capacity / totalWeight;
            var satisfied = new ArrayList<UUID>();
            for (var entry : open.entrySet()) {
                if (entry.getValue() <= level * weights.getOrDefault(entry.getKey(), 1)) {
                    satisfied.add(entry.getKey());
                }
            }
            if (satisfied.isEmpty()) {
                for (var id : open.keySet()) {
                    entitled.put(id, level * weights.getOrDefault(id, 1));
                }
                return entitled;
            }
            for (var id : satisfied) {
                entitled.put(id, open.get(id));
                capacity -= open.remove(id);
            }
        }
        open.keySet().forEach(id -> entitled.put(id, 0.0));
        return entitled;
    }

    /** Jain's index over each project's received / entitled service: 1 when every project got its fair share. */
    private Double jainsIndex() {
        var rates = new ArrayList<Double>();
        new TreeMap<>(service).forEach((project, totals) -> {
            if (totals[1] > 0) {
                rates.add(totals[0] / totals[1]);
            }
        });
        if (rates.size() < 2) {
            return null;
        }
        var sum = rates.stream().mapToDouble(Double::doubleValue).sum();
        var sumOfSquares = rates.stream().mapToDouble(rate -> rate * rate).sum();
        return sumOfSquares == 0 ? null : (sum * sum) / (rates.size() * sumOfSquares);
    }

    /** A job's dominant share of the whole fleet: the measure of service the fairness index is computed on. */
    double dominantShare(io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Demand demand) {
        var share = Math.max(demand.cpuMillis() / (double) totalCpu, demand.memoryMib() / (double) totalMemory);
        share = Math.max(share, 1.0 / totalSlots);
        if (totalAccelerators > 0) {
            share = Math.max(share, demand.accelerators() / (double) totalAccelerators);
        }
        return share;
    }

    private PlanningTime planningTime() {
        if (planningNanos.isEmpty()) {
            return new PlanningTime(0, 0, 0);
        }
        var sorted = planningNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        var mean = Arrays.stream(sorted).average().orElse(0) / 1_000.0;
        return new PlanningTime(sorted.length, mean, percentile(sorted, 0.99) / 1_000.0);
    }

    private static Distribution distribution(List<Long> values) {
        if (values.isEmpty()) {
            return Distribution.empty();
        }
        var sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        var mean = Arrays.stream(sorted).average().orElse(0);
        return new Distribution(
                sorted.length,
                mean,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted[sorted.length - 1]);
    }

    /** Nearest-rank percentile: always an observed value, so it is exact and reproducible. */
    private static long percentile(long[] sorted, double p) {
        var rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    /**
     * SHA-256 over the metrics and every job's outcome in trace order. {@code Record.toString} is fully determined by
     * the component values (doubles print exactly), so equal results hash equally on any JVM.
     */
    private static String hash(SimulationResult.Metrics metrics, List<JobOutcome> outcomes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(metrics.toString().getBytes(StandardCharsets.UTF_8));
            for (var outcome : outcomes) {
                digest.update(outcome.toString().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
