package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.PendingJob;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingSnapshot.WorkerCandidate;
import java.util.Comparator;

/**
 * A policy is a job ordering (who is considered first) combined with a worker selection (where a job goes among the
 * workers it fits on). Each pair is a deliberate trade-off rather than a "better" algorithm; the simulator exists to
 * show those trade-offs on the same workload (docs/SPEC.md §7).
 */
public enum SchedulingPolicy {
    /** Oldest first, on the first worker that fits. Simple and predictable; ignores priority and packing. */
    FIFO(JobOrdering.FIFO, WorkerSelection.FIRST_FIT),
    /** Highest priority first. Strict: a steady stream of urgent work can starve low priorities indefinitely. */
    PRIORITY(JobOrdering.PRIORITY, WorkerSelection.FIRST_FIT),
    /** Oldest first, spread to the least utilised worker. Good latency and headroom; fragments capacity. */
    LEAST_LOADED(JobOrdering.FIFO, WorkerSelection.LEAST_LOADED),
    /**
     * Oldest first, packed onto the fullest worker that fits, keeping accelerator workers free for accelerator jobs.
     * Leaves large contiguous capacity for big jobs; concentrates load and its failure blast radius.
     */
    BIN_PACKING(JobOrdering.FIFO, WorkerSelection.BEST_FIT_CONSERVING_ACCELERATORS);

    private final JobOrdering ordering;
    private final WorkerSelection selection;

    SchedulingPolicy(JobOrdering ordering, WorkerSelection selection) {
        this.ordering = ordering;
        this.selection = selection;
    }

    public JobOrdering ordering() {
        return ordering;
    }

    public WorkerSelection selection() {
        return selection;
    }

    public enum JobOrdering {
        FIFO(Comparator.comparing(PendingJob::availableAt).thenComparing(PendingJob::id)),
        PRIORITY(Comparator.comparingInt(PendingJob::priority)
                .reversed()
                .thenComparing(PendingJob::availableAt)
                .thenComparing(PendingJob::id));

        private final Comparator<PendingJob> comparator;

        JobOrdering(Comparator<PendingJob> comparator) {
            this.comparator = comparator;
        }

        public Comparator<PendingJob> comparator() {
            return comparator;
        }
    }

    /** Lower score wins; ties go to the lower worker id, so the choice is always reproducible. */
    public enum WorkerSelection {
        FIRST_FIT {
            @Override
            double score(PendingJob job, WorkerCandidate worker, Resources freeNow) {
                return 0;
            }
        },
        LEAST_LOADED {
            @Override
            double score(PendingJob job, WorkerCandidate worker, Resources freeNow) {
                return utilisationAfter(worker.capacity(), freeNow, job.demand());
            }
        },
        BEST_FIT_CONSERVING_ACCELERATORS {
            @Override
            double score(PendingJob job, WorkerCandidate worker, Resources freeNow) {
                // A job that needs no accelerator should not occupy a slot on a machine that has them: that slot may
                // be the only place an accelerator job can run. The penalty outweighs any packing benefit.
                var wastesAccelerators =
                        job.demand().accelerators() == 0 && worker.capacity().accelerators() > 0;
                return (wastesAccelerators ? 10 : 0) + (1 - utilisationAfter(worker.capacity(), freeNow, job.demand()));
            }
        };

        abstract double score(PendingJob job, WorkerCandidate worker, Resources freeNow);

        /**
         * Dominant-resource utilisation after placing the job: the highest fraction in use across CPU, memory,
         * accelerators (if the worker has any) and slots. One number per worker makes heterogeneous workers comparable.
         */
        static double utilisationAfter(Resources capacity, Resources freeNow, Demand demand) {
            var after = freeNow.minus(demand);
            var dominant = Math.max(
                    used(capacity.cpuMillis(), after.cpuMillis()), used(capacity.memoryMib(), after.memoryMib()));
            dominant = Math.max(dominant, used(capacity.slots(), after.slots()));
            if (capacity.accelerators() > 0) {
                dominant = Math.max(dominant, used(capacity.accelerators(), after.accelerators()));
            }
            return dominant;
        }

        private static double used(int capacity, int free) {
            return (capacity - free) / (double) capacity;
        }
    }
}
