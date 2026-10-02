package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.CandidateEvaluation;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Outcome;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Verdict;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.ProjectState;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Turns a snapshot into placement decisions under a policy. Pure and deterministic: the same snapshot and policy
 * always produce the same plan, which is what lets the simulator replay a workload through exactly this code.
 *
 * <p>Jobs are considered in policy order, and each placement immediately reduces the chosen worker's free capacity
 * (and its project's quota headroom) for the jobs after it, so one plan never promises the same capacity twice. A job
 * that does not fit does not block the jobs behind it (backfilling).
 *
 * <p><b>Fair share.</b> FAIR_SHARE is start-time fair queuing over projects. Each project has a virtual time {@code v}:
 * the service it received divided by its weight. The planner repeatedly takes the backlogged project with the
 * smallest {@code v} and considers its oldest job; a placement charges {@code cost / weight}, where cost is the job's
 * dominant share of the fleet's total capacity times a default duration estimate (jobs carry no duration, so every
 * job is estimated alike; the estimate only scales {@code v}). A project that floods the queue therefore raises only its
 * own {@code v}, and the others keep being served in proportion to their weights. Before planning, every project's
 * {@code v} is raised to the system virtual time (the smallest {@code v} among backlogged projects at the end of the
 * previous cycle): a project returning from idle starts level with the others instead of cashing in its idle time.
 */
public final class PlacementPlanner {

    /** Decision records keep at most this many candidates; fleets are small, but records must stay bounded. */
    public static final int MAX_CANDIDATES_RECORDED = 16;

    /** The duration every job is assumed to take when charging fair-share service. */
    static final double DEFAULT_ESTIMATED_SECONDS = 60;

    /**
     * @param virtualTimes each snapshot project's virtual time after this plan (floor applied, placements charged).
     * @param systemVirtualTime the floor for the next cycle; never decreases.
     */
    public record Plan(List<PlacementDecision> decisions, Map<UUID, Double> virtualTimes, double systemVirtualTime) {}

    private PlacementPlanner() {}

    public static List<PlacementDecision> planPlacements(SchedulingSnapshot snapshot, SchedulingPolicy policy) {
        return plan(snapshot, policy).decisions();
    }

    public static Plan plan(SchedulingSnapshot snapshot, SchedulingPolicy policy) {
        return new Planning(snapshot, policy).run();
    }

    /** The mutable state of one planning pass, discarded when it ends. */
    private static final class Planning {

        private final SchedulingSnapshot snapshot;
        private final SchedulingPolicy policy;
        private final Map<UUID, Resources> free = new HashMap<>();
        private final Map<UUID, Usage> usage = new HashMap<>();
        private final Map<UUID, Double> virtualTimes = new LinkedHashMap<>();
        private final Resources fleet;

        Planning(SchedulingSnapshot snapshot, SchedulingPolicy policy) {
            this.snapshot = snapshot;
            this.policy = policy;
            snapshot.workers().forEach(worker -> free.put(worker.id(), worker.free()));
            this.fleet = fleetCapacity(snapshot.workers());
            for (var job : snapshot.jobs()) {
                var project = snapshot.projectOf(job);
                usage.computeIfAbsent(
                        project.id(), id -> new Usage(project.runningJobs(), project.acceleratorsInUse()));
                virtualTimes.computeIfAbsent(
                        project.id(), id -> Math.max(project.virtualTime(), snapshot.systemVirtualTime()));
            }
        }

        Plan run() {
            var decisions = policy == SchedulingPolicy.FAIR_SHARE ? planFairly() : planInOrder();
            var floor = virtualTimes.values().stream()
                    .mapToDouble(Double::doubleValue)
                    .min()
                    .orElse(snapshot.systemVirtualTime());
            return new Plan(decisions, Map.copyOf(virtualTimes), Math.max(snapshot.systemVirtualTime(), floor));
        }

        private List<PlacementDecision> planInOrder() {
            var ordered = snapshot.jobs().stream()
                    .sorted(policy.ordering().comparator())
                    .toList();
            var decisions = new ArrayList<PlacementDecision>(ordered.size());
            for (var job : ordered) {
                decisions.add(decideAndApply(job));
            }
            return decisions;
        }

        /** Repeatedly serves the project with the least virtual time; ties go to the lower project id. */
        private List<PlacementDecision> planFairly() {
            Map<UUID, ArrayDeque<PendingJob>> queues = new LinkedHashMap<>();
            snapshot.jobs().stream()
                    .sorted(SchedulingPolicy.JobOrdering.FIFO.comparator())
                    .forEach(job -> queues.computeIfAbsent(job.projectId(), id -> new ArrayDeque<>())
                            .add(job));
            var next = new PriorityQueue<UUID>(
                    Comparator.<UUID>comparingDouble(virtualTimes::get).thenComparing(Comparator.naturalOrder()));
            next.addAll(queues.keySet());

            var decisions = new ArrayList<PlacementDecision>(snapshot.jobs().size());
            while (!next.isEmpty()) {
                var projectId = next.poll();
                var queue = queues.get(projectId);
                decisions.add(decideAndApply(queue.poll()));
                if (!queue.isEmpty()) {
                    // Re-inserted after the charge, so the queue sees the project's new virtual time.
                    next.add(projectId);
                }
            }
            return decisions;
        }

        private PlacementDecision decideAndApply(PendingJob job) {
            var project = snapshot.projectOf(job);
            var decision = decide(job, project);
            if (decision.chosenWorkerId() != null) {
                free.computeIfPresent(decision.chosenWorkerId(), (id, resources) -> resources.minus(job.demand()));
                usage.get(project.id()).add(job.demand());
                virtualTimes.merge(project.id(), charge(job, project), Double::sum);
            }
            return decision;
        }

        private PlacementDecision decide(PendingJob job, ProjectState project) {
            var evaluations =
                    new ArrayList<CandidateEvaluation>(snapshot.workers().size());
            CandidateEvaluation best = null;
            for (var worker : snapshot.workers()) {
                var evaluation = evaluate(job, worker, free.get(worker.id()));
                evaluations.add(evaluation);
                if (evaluation.verdict() == Verdict.FITS && (best == null || isBetter(evaluation, best))) {
                    best = evaluation;
                }
            }

            var compatible =
                    evaluations.stream().filter(e -> e.verdict().isCompatible()).count();
            if (compatible == 0) {
                return new PlacementDecision(
                        job, Outcome.UNSCHEDULABLE, null, unschedulableReason(job, evaluations), bounded(evaluations));
            }
            var quotaBlock = quotaBlock(job, project);
            if (quotaBlock != null) {
                return new PlacementDecision(job, Outcome.WAITING_FOR_QUOTA, null, quotaBlock, bounded(evaluations));
            }
            if (best != null) {
                var chosen = best;
                var recorded = new ArrayList<CandidateEvaluation>(evaluations.size());
                recorded.add(new CandidateEvaluation(
                        chosen.workerId(), chosen.workerName(), Verdict.CHOSEN, chosen.detail(), chosen.score()));
                evaluations.stream().filter(e -> e != chosen).forEach(recorded::add);
                var reason = "Placed on " + chosen.workerName() + " by " + policy + " (" + chosen.detail()
                        + policyContext(job, project) + ")";
                return new PlacementDecision(job, Outcome.PLACED, chosen.workerId(), reason, bounded(recorded));
            }
            var reason = compatible + " compatible worker" + (compatible == 1 ? "" : "s")
                    + ", none can take it now: "
                    + firstDetail(evaluations, Verdict.INSUFFICIENT_FREE_CAPACITY, Verdict.NOT_ACCEPTING_WORK);
            return new PlacementDecision(job, Outcome.WAITING_FOR_CAPACITY, null, reason, bounded(evaluations));
        }

        /** Why the project's quota holds this job back, or null when it does not. */
        private @Nullable String quotaBlock(PendingJob job, ProjectState project) {
            var used = usage.get(project.id());
            if (project.maxRunningJobs() != null && used.running >= project.maxRunningJobs()) {
                return "Project " + project.name() + " is at its quota of " + project.maxRunningJobs() + " running job"
                        + (project.maxRunningJobs() == 1 ? "" : "s");
            }
            var requested = job.demand().accelerators();
            if (project.maxAccelerators() != null
                    && requested > 0
                    && used.accelerators + requested > project.maxAccelerators()) {
                return "Project " + project.name() + " would exceed its quota of " + project.maxAccelerators()
                        + " accelerators (" + used.accelerators + " in use, " + requested + " requested)";
            }
            return null;
        }

        /** What the policy weighed beyond the worker itself, for the decision record. */
        private String policyContext(PendingJob job, ProjectState project) {
            return switch (policy) {
                case FAIR_SHARE ->
                    String.format(
                            Locale.ROOT,
                            "; project %s at virtual time %.3f, weight %d",
                            project.name(),
                            virtualTimes.get(project.id()),
                            project.weight());
                case DEADLINE -> job.deadline() == null ? "; no deadline" : "; " + describeDeadline(job);
                case PRIORITY -> "; priority " + job.priority();
                default -> "";
            };
        }

        private String describeDeadline(PendingJob job) {
            var left = Duration.between(snapshot.now(), job.deadline());
            return left.isNegative()
                    ? "deadline passed " + humanize(left.negated()) + " ago"
                    : "deadline in " + humanize(left);
        }

        private CandidateEvaluation evaluate(PendingJob job, WorkerCandidate worker, Resources freeNow) {
            if (!worker.labels().containsAll(job.requiredLabels())) {
                var missing = new TreeSet<>(job.requiredLabels());
                missing.removeAll(worker.labels());
                return new CandidateEvaluation(
                        worker.id(), worker.name(), Verdict.MISSING_LABELS, "missing labels " + missing, null);
            }
            if (!worker.capacity().covers(job.demand())) {
                return new CandidateEvaluation(
                        worker.id(),
                        worker.name(),
                        Verdict.EXCEEDS_CAPACITY,
                        worker.capacity().firstShortfall(job.demand(), "in total"),
                        null);
            }
            if (!worker.acceptingWork()) {
                return new CandidateEvaluation(
                        worker.id(),
                        worker.name(),
                        Verdict.NOT_ACCEPTING_WORK,
                        String.valueOf(worker.notAcceptingReason()),
                        null);
            }
            if (!freeNow.covers(job.demand())) {
                return new CandidateEvaluation(
                        worker.id(),
                        worker.name(),
                        Verdict.INSUFFICIENT_FREE_CAPACITY,
                        freeNow.firstShortfall(job.demand(), "free"),
                        null);
            }
            var score = policy.selection().score(job, worker, freeNow);
            var utilisation =
                    SchedulingPolicy.WorkerSelection.utilisationAfter(worker.capacity(), freeNow, job.demand());
            var detail = "fits; " + Math.round(utilisation * 100) + "% utilised after placement";
            return new CandidateEvaluation(worker.id(), worker.name(), Verdict.FITS, detail, score);
        }

        /**
         * Service charged for one placement: the job's dominant share of the whole fleet (CPU, memory, slots and,
         * when the fleet has any, accelerators) times the duration estimate, divided by the project's weight.
         */
        private double charge(PendingJob job, ProjectState project) {
            var demand = job.demand();
            var share = Math.max(
                    ratio(demand.cpuMillis(), fleet.cpuMillis()), ratio(demand.memoryMib(), fleet.memoryMib()));
            share = Math.max(share, ratio(1, fleet.slots()));
            if (fleet.accelerators() > 0) {
                share = Math.max(share, ratio(demand.accelerators(), fleet.accelerators()));
            }
            return share * DEFAULT_ESTIMATED_SECONDS / project.weight();
        }
    }

    /** Running jobs and accelerators of one project, updated as the plan places its jobs. */
    private static final class Usage {
        int running;
        int accelerators;

        Usage(int running, int accelerators) {
            this.running = running;
            this.accelerators = accelerators;
        }

        void add(Demand demand) {
            running++;
            accelerators += demand.accelerators();
        }
    }

    private static Resources fleetCapacity(List<WorkerCandidate> workers) {
        int cpu = 0;
        int memory = 0;
        int accelerators = 0;
        int slots = 0;
        for (var worker : workers) {
            cpu += worker.capacity().cpuMillis();
            memory += worker.capacity().memoryMib();
            accelerators += worker.capacity().accelerators();
            slots += worker.capacity().slots();
        }
        return new Resources(cpu, memory, accelerators, slots);
    }

    private static double ratio(int part, int whole) {
        return whole <= 0 ? 0 : part / (double) whole;
    }

    private static boolean isBetter(CandidateEvaluation candidate, CandidateEvaluation incumbent) {
        var byScore = Double.compare(candidate.score(), incumbent.score());
        return byScore < 0 || (byScore == 0 && candidate.workerId().compareTo(incumbent.workerId()) < 0);
    }

    private static String unschedulableReason(PendingJob job, List<CandidateEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return "No live worker is registered";
        }
        var anyWithLabels = evaluations.stream().anyMatch(e -> e.verdict() != Verdict.MISSING_LABELS);
        if (!anyWithLabels) {
            return "No live worker has the required labels " + new TreeSet<>(job.requiredLabels());
        }
        return "No live worker is large enough: " + firstDetail(evaluations, Verdict.EXCEEDS_CAPACITY);
    }

    private static String firstDetail(List<CandidateEvaluation> evaluations, Verdict... verdicts) {
        for (var verdict : verdicts) {
            for (var evaluation : evaluations) {
                if (evaluation.verdict() == verdict) {
                    return evaluation.workerName() + " " + evaluation.detail();
                }
            }
        }
        return "no detail";
    }

    private static String humanize(Duration duration) {
        var seconds = duration.toSeconds();
        if (seconds < 120) {
            return seconds + "s";
        }
        if (seconds < 7200) {
            return (seconds / 60) + "m";
        }
        return (seconds / 3600) + "h";
    }

    /** Keeps the most informative candidates when a fleet is larger than the record limit. */
    private static List<CandidateEvaluation> bounded(List<CandidateEvaluation> evaluations) {
        if (evaluations.size() <= MAX_CANDIDATES_RECORDED) {
            return List.copyOf(evaluations);
        }
        return evaluations.stream()
                .sorted(Comparator.comparingInt(e -> e.verdict().ordinal()))
                .limit(MAX_CANDIDATES_RECORDED)
                .toList();
    }
}
