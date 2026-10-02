package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.CandidateEvaluation;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Outcome;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.PlacementDecision.Verdict;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns a snapshot into placement decisions under a policy. Pure and deterministic: the same snapshot and policy
 * always produce the same decisions, which is what lets the simulator replay a workload through exactly this code.
 *
 * <p>Jobs are considered in policy order, and each placement immediately reduces the chosen worker's free capacity
 * for the jobs after it, so one plan can never promise the same capacity twice. A job that does not fit does not
 * block the jobs behind it (backfilling). This keeps utilisation high but means a large job can wait while smaller
 * ones overtake it; the fairness and deadline policies of Phase 7 address that trade-off.
 */
public final class PlacementPlanner {

    /** Decision records keep at most this many candidates; fleets are small, but records must stay bounded. */
    public static final int MAX_CANDIDATES_RECORDED = 16;

    private PlacementPlanner() {}

    public static List<PlacementDecision> planPlacements(SchedulingSnapshot snapshot, SchedulingPolicy policy) {
        Map<UUID, Resources> free = new HashMap<>();
        snapshot.workers().forEach(worker -> free.put(worker.id(), worker.free()));

        var ordered =
                snapshot.jobs().stream().sorted(policy.ordering().comparator()).toList();
        var decisions = new ArrayList<PlacementDecision>(ordered.size());
        for (var job : ordered) {
            var decision = decide(job, snapshot.workers(), free, policy);
            if (decision.chosenWorkerId() != null) {
                free.computeIfPresent(decision.chosenWorkerId(), (id, resources) -> resources.minus(job.demand()));
            }
            decisions.add(decision);
        }
        return decisions;
    }

    private static PlacementDecision decide(
            PendingJob job, List<WorkerCandidate> workers, Map<UUID, Resources> free, SchedulingPolicy policy) {
        var evaluations = new ArrayList<CandidateEvaluation>(workers.size());
        CandidateEvaluation best = null;
        for (var worker : workers) {
            var evaluation = evaluate(job, worker, free.get(worker.id()), policy);
            evaluations.add(evaluation);
            if (evaluation.verdict() == Verdict.FITS && (best == null || isBetter(evaluation, best))) {
                best = evaluation;
            }
        }

        if (best != null) {
            var chosen = best;
            var recorded = new ArrayList<CandidateEvaluation>(evaluations.size());
            recorded.add(new CandidateEvaluation(
                    chosen.workerId(), chosen.workerName(), Verdict.CHOSEN, chosen.detail(), chosen.score()));
            evaluations.stream().filter(e -> e != chosen).forEach(recorded::add);
            var reason = "Placed on " + chosen.workerName() + " by " + policy + " (" + chosen.detail() + ")";
            return new PlacementDecision(job, Outcome.PLACED, chosen.workerId(), reason, bounded(recorded));
        }

        var compatible =
                evaluations.stream().filter(e -> e.verdict().isCompatible()).count();
        if (compatible == 0) {
            return new PlacementDecision(
                    job, Outcome.UNSCHEDULABLE, null, unschedulableReason(job, evaluations), bounded(evaluations));
        }
        var reason = compatible + " compatible worker" + (compatible == 1 ? "" : "s")
                + ", none can take it now: "
                + firstDetail(evaluations, Verdict.INSUFFICIENT_FREE_CAPACITY, Verdict.NOT_ACCEPTING_WORK);
        return new PlacementDecision(job, Outcome.WAITING_FOR_CAPACITY, null, reason, bounded(evaluations));
    }

    private static CandidateEvaluation evaluate(
            PendingJob job, WorkerCandidate worker, Resources freeNow, SchedulingPolicy policy) {
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
        var utilisation = SchedulingPolicy.WorkerSelection.utilisationAfter(worker.capacity(), freeNow, job.demand());
        var detail = "fits; " + Math.round(utilisation * 100) + "% utilised after placement";
        return new CandidateEvaluation(worker.id(), worker.name(), Verdict.FITS, detail, score);
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
