package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The answer to "why did job X run on worker Y and not on worker Z?" for one job in one planning pass.
 *
 * @param candidates per-worker verdicts, chosen worker first, capped at
 *     {@link PlacementPlanner#MAX_CANDIDATES_RECORDED}.
 */
public record PlacementDecision(
        PendingJob job,
        Outcome outcome,
        @Nullable UUID chosenWorkerId,
        String reason,
        List<CandidateEvaluation> candidates) {

    public enum Outcome {
        PLACED,
        /** Compatible workers exist but none has enough free capacity now: the job stays queued. */
        WAITING_FOR_CAPACITY,
        /** It could run now, but its project is at a quota (running jobs or accelerators): it waits for its own work. */
        WAITING_FOR_QUOTA,
        /** No live worker could run it even if idle (labels or size): it stays queued, flagged for the operator. */
        UNSCHEDULABLE
    }

    public enum Verdict {
        CHOSEN,
        FITS,
        INSUFFICIENT_FREE_CAPACITY,
        NOT_ACCEPTING_WORK,
        EXCEEDS_CAPACITY,
        MISSING_LABELS;

        /** Could run this job if it were idle and accepting work. */
        boolean isCompatible() {
            return this != EXCEEDS_CAPACITY && this != MISSING_LABELS;
        }
    }

    /** @param score the selection score when the worker fit (lower wins), otherwise null. */
    public record CandidateEvaluation(
            UUID workerId,
            String workerName,
            Verdict verdict,
            String detail,
            @Nullable Double score) {}
}
