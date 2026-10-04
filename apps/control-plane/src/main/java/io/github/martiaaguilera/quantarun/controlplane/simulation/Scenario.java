package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Demand;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.Resources;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.Failure;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.TraceJob;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.TraceProject;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.TraceWorker;
import io.github.martiaaguilera.quantarun.controlplane.simulation.Trace.WorkerOutage;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The built-in scenarios (docs/SPEC.md §11). Each turns a seed and a job count into a {@link Trace}, using one
 * {@link SplittableRandom} and no clock, so the same seed always produces the same trace.
 *
 * <p>Every scenario runs on the demo fleet: a CPU worker, a mixed worker with one accelerator and an accelerator worker
 * with two (the same shapes as the compose stack), ten execution slots in all.
 */
public enum Scenario {
    /** Poisson arrivals at about 70% of the fleet's slot capacity, three equal projects. The baseline. */
    STEADY {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1, 1);
            trace.poissonArrivals(
                    0.7,
                    10_000,
                    (index, random) -> trace.job(
                            projects.get(random.nextInt(projects.size())),
                            small(random),
                            Set.of(),
                            random.nextInt(10),
                            10_000));
        }
    },
    /** Every job arrives within the first five seconds: how fast each policy drains a backlog. */
    BURST {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.jobsAtRandomTimes(
                    5_000,
                    (index, random) -> trace.job(
                            projects.get(index % 2),
                            small(random),
                            Set.of(),
                            random.nextInt(10),
                            2_000 + random.nextLong(18_000)));
        }
    },
    /** CPU-heavy, memory-heavy and accelerator jobs mixed: where packing and spreading differ most. */
    MIXED_RESOURCES {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.poissonArrivals(0.8, 12_000, (index, random) -> {
                var kind = random.nextInt(10);
                Demand demand;
                Set<String> labels = Set.of();
                if (kind < 2) {
                    demand = new Demand(1_000, 12_000 + random.nextInt(4_000), 0);
                } else if (kind < 3) {
                    demand = new Demand(1_000, 4_096, 1);
                    labels = Set.of("cuda");
                } else {
                    demand = new Demand(250 + random.nextInt(2_750), 256 + random.nextInt(3_840), 0);
                }
                return trace.job(projects.get(index % 2), demand, labels, random.nextInt(10), 12_000);
            });
        }
    },
    /** Half the jobs need an accelerator and the fleet has three: who gets the scarce slots, and who waits. */
    ACCELERATOR_SCARCE {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.poissonArrivals(0.9, 10_000, (index, random) -> {
                var kind = random.nextInt(10);
                if (kind < 4) {
                    return trace.job(
                            projects.get(0), new Demand(1_000, 4_096, 1), Set.of("cuda"), random.nextInt(10), 10_000);
                }
                if (kind < 5) {
                    return trace.job(
                            projects.get(0),
                            new Demand(2_000, 16_384, 2),
                            Set.of("cuda", "large-model"),
                            random.nextInt(10),
                            10_000);
                }
                return trace.job(projects.get(1), small(random), Set.of(), random.nextInt(10), 10_000);
            });
        }
    },
    /**
     * One project dumps 80% of all jobs in the first ten seconds; two others submit a steady trickle. Fairness between
     * tenants is what this scenario measures.
     */
    NOISY_NEIGHBOR {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1, 1);
            var noisy = (int) Math.round(trace.jobCount * 0.8);
            var quietSpacing = Math.max(1, trace.expectedHorizonMs(0.9, 8_000) / Math.max(1, trace.jobCount - noisy));
            trace.custom(random -> {
                for (int i = 0; i < trace.jobCount; i++) {
                    if (i < noisy) {
                        trace.add(
                                trace.job(projects.get(0), random.nextLong(10_000), small(random), Set.of(), 4, 8_000));
                    } else {
                        var quiet = i - noisy;
                        trace.add(trace.job(
                                projects.get(1 + quiet % 2), quiet * quietSpacing, small(random), Set.of(), 4, 8_000));
                    }
                }
            });
        }
    },
    /** Most jobs carry a tight deadline, at about 90% load: the deadline miss rate is the metric that matters. */
    DEADLINE_HEAVY {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.poissonArrivals(0.9, 10_000, (index, random) -> {
                var job = trace.job(projects.get(index % 2), small(random), Set.of(), random.nextInt(10), 10_000);
                if (random.nextInt(10) < 8) {
                    var slack = (long) (job.durationMs() * (1.5 + random.nextDouble() * 3));
                    return trace.withDeadline(job, job.arrivalMs() + slack);
                }
                return job;
            });
        }
    },
    /** A steady load, and the mixed worker crashes a quarter of the way in, coming back a minute later. */
    WORKER_FAILURE {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.poissonArrivals(
                    0.6,
                    10_000,
                    (index, random) ->
                            trace.job(projects.get(index % 2), small(random), Set.of(), random.nextInt(10), 10_000));
            var crashAt = trace.expectedHorizonMs(0.6, 10_000) / 4;
            trace.outage(MIXED, crashAt, crashAt + 60_000);
        }
    },
    /**
     * An external provider rate-limits 40% of first attempts with a Retry-After, and 5% of jobs fail transiently twice.
     */
    RATE_LIMIT {
        @Override
        void generate(Builder trace) {
            var projects = trace.projects(1, 1);
            trace.poissonArrivals(0.7, 8_000, (index, random) -> {
                var job = trace.job(projects.get(index % 2), small(random), Set.of(), random.nextInt(10), 8_000);
                var roll = random.nextInt(100);
                if (roll < 40) {
                    return trace.withFailures(
                            job,
                            Map.of(1, new Failure(FailureClass.RATE_LIMITED, 200, 5_000 + random.nextLong(15_000))));
                }
                if (roll < 45) {
                    return trace.withFailures(
                            job,
                            Map.of(
                                    1, new Failure(FailureClass.TRANSIENT, 1_000, null),
                                    2, new Failure(FailureClass.TRANSIENT, 1_000, null)));
                }
                return job;
            });
        }
    };

    public static final int DEFAULT_JOBS = 2_000;
    public static final int MAX_JOBS = 20_000;

    static final UUID CPU = new UUID(0x51_0000_0000_7000L, 1);
    static final UUID MIXED = new UUID(0x51_0000_0000_7000L, 2);
    static final UUID ACCEL = new UUID(0x51_0000_0000_7000L, 3);

    /** The demo fleet: the same three worker shapes as docker-compose.yml. */
    static final List<TraceWorker> FLEET = List.of(
            new TraceWorker(CPU, "worker-cpu", Set.of(), new Resources(4_000, 8_192, 0, 4)),
            new TraceWorker(MIXED, "worker-mixed", Set.of("cuda"), new Resources(8_000, 16_384, 1, 4)),
            new TraceWorker(ACCEL, "worker-accel", Set.of("cuda", "large-model"), new Resources(8_000, 32_768, 2, 2)));

    public Trace trace(long seed, int jobCount) {
        if (jobCount < 1 || jobCount > MAX_JOBS) {
            throw new IllegalArgumentException("jobCount must be between 1 and " + MAX_JOBS);
        }
        var builder = new Builder(this, seed, jobCount);
        generate(builder);
        return builder.build();
    }

    abstract void generate(Builder trace);

    private static Demand small(SplittableRandom random) {
        return new Demand(250 + random.nextInt(750), 256 + random.nextInt(768), 0);
    }

    /** Accumulates one trace. Every random draw goes through the single generator, in a fixed order. */
    static final class Builder {

        private final Scenario scenario;
        private final SplittableRandom random;
        private final int jobCount;
        private final List<TraceProject> projects = new ArrayList<>();
        private final List<TraceJob> jobs = new ArrayList<>();
        private final List<WorkerOutage> outages = new ArrayList<>();

        Builder(Scenario scenario, long seed, int jobCount) {
            this.scenario = scenario;
            this.random = new SplittableRandom(seed ^ ((long) scenario.ordinal() << 48));
            this.jobCount = jobCount;
        }

        interface JobFactory {
            TraceJob create(int index, SplittableRandom random);
        }

        List<TraceProject> projects(int... weights) {
            for (int i = 0; i < weights.length; i++) {
                projects.add(new TraceProject(
                        new UUID(0x50_0000_0000_7000L, i + 1L), "tenant-" + (char) ('a' + i), weights[i]));
            }
            return List.copyOf(projects);
        }

        /**
         * Arrivals as a Poisson process sized to keep the fleet's ten slots at {@code load} on average, given the mean
         * duration of the jobs.
         */
        void poissonArrivals(double load, long meanDurationMs, JobFactory factory) {
            var meanGapMs = meanDurationMs / (load * slots());
            var time = 0.0;
            for (int i = 0; i < jobCount; i++) {
                time += -meanGapMs * Math.log(1 - random.nextDouble());
                var job = factory.create(i, random);
                jobs.add(at(job, (long) time));
            }
        }

        void jobsAtRandomTimes(long windowMs, JobFactory factory) {
            for (int i = 0; i < jobCount; i++) {
                var job = factory.create(i, random);
                jobs.add(at(job, random.nextLong(windowMs)));
            }
        }

        void custom(java.util.function.Consumer<SplittableRandom> generator) {
            generator.accept(random);
        }

        void add(TraceJob job) {
            jobs.add(job);
        }

        void outage(UUID workerId, long downAtMs, long upAtMs) {
            outages.add(new WorkerOutage(workerId, downAtMs, upAtMs));
        }

        long expectedHorizonMs(double load, long meanDurationMs) {
            return (long) (jobCount * meanDurationMs / (load * slots()));
        }

        /** A job of the given shape whose duration is drawn around {@code meanDurationMs} (uniform, ±50%). */
        TraceJob job(TraceProject project, Demand demand, Set<String> labels, int priority, long meanDurationMs) {
            return job(project, 0, demand, labels, priority, meanDurationMs);
        }

        TraceJob job(
                TraceProject project,
                long arrivalMs,
                Demand demand,
                Set<String> labels,
                int priority,
                long meanDurationMs) {
            var duration = meanDurationMs / 2 + random.nextLong(meanDurationMs);
            return new TraceJob(
                    new UUID(0x52_0000_0000_7000L, jobs.size() + 1L),
                    project.id(),
                    arrivalMs,
                    demand,
                    labels,
                    priority,
                    null,
                    duration,
                    3,
                    Map.of());
        }

        TraceJob withDeadline(TraceJob job, long deadlineMs) {
            return new TraceJob(
                    job.id(),
                    job.projectId(),
                    job.arrivalMs(),
                    job.demand(),
                    job.requiredLabels(),
                    job.priority(),
                    deadlineMs,
                    job.durationMs(),
                    job.maxAttempts(),
                    job.failures());
        }

        TraceJob withFailures(TraceJob job, Map<Integer, Failure> failures) {
            return new TraceJob(
                    job.id(),
                    job.projectId(),
                    job.arrivalMs(),
                    job.demand(),
                    job.requiredLabels(),
                    job.priority(),
                    job.deadlineMs(),
                    job.durationMs(),
                    job.maxAttempts(),
                    new HashMap<>(failures));
        }

        private static TraceJob at(TraceJob job, long arrivalMs) {
            return new TraceJob(
                    job.id(),
                    job.projectId(),
                    arrivalMs,
                    job.demand(),
                    job.requiredLabels(),
                    job.priority(),
                    job.deadlineMs() == null ? null : job.deadlineMs() + arrivalMs - job.arrivalMs(),
                    job.durationMs(),
                    job.maxAttempts(),
                    job.failures());
        }

        private static int slots() {
            return FLEET.stream().mapToInt(worker -> worker.capacity().slots()).sum();
        }

        Trace build() {
            return new Trace(projects, FLEET, jobs, outages);
        }
    }
}
