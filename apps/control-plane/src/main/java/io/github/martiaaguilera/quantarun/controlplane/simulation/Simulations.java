package io.github.martiaaguilera.quantarun.controlplane.simulation;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import java.util.List;

/**
 * Replays one scenario against several policies. Pure computation: the same arguments give the same
 * {@link SimulationResult} apart from the measured planning time.
 */
public final class Simulations {

    private Simulations() {}

    /** Every policy replays the identical trace, so differences in the results come from the policies alone. */
    public static SimulationResult run(Scenario scenario, long seed, int jobCount, List<SchedulingPolicy> policies) {
        var trace = scenario.trace(seed, jobCount);
        var results = policies.stream()
                .distinct()
                .map(policy -> new Simulator(trace, policy, seed).run())
                .toList();
        return new SimulationResult(scenario.name(), seed, jobCount, results);
    }
}
