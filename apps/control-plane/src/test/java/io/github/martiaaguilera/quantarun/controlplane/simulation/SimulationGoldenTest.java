package io.github.martiaaguilera.quantarun.controlplane.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the result hash of every scenario under every policy (seed 42, 1,000 jobs). The planner and the simulator may be
 * made faster, never different: a refactor that changes any placement, retry or metric changes a hash here. The hashes
 * were recorded on 2026-10-06 (commit 8d4a95f), before the planner's lean mode existed. A deliberate change of
 * behaviour updates the file and says why in the commit.
 */
class SimulationGoldenTest {

    private static final Map<String, String> GOLDEN = load();

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyPolicy_reproducesItsRecordedResult(Scenario scenario) {
        var result = Simulations.run(scenario, 42, 1000, List.of(SchedulingPolicy.values()));

        for (var policy : result.policies()) {
            assertThat(policy.resultHash())
                    .as("%s under %s", scenario, policy.policy())
                    .isEqualTo(GOLDEN.get(scenario + " " + policy.policy()));
        }
    }

    private static Map<String, String> load() {
        try (var in = SimulationGoldenTest.class.getResourceAsStream("/simulation/golden-hashes.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(line -> line.split(" "))
                    .collect(Collectors.toMap(parts -> parts[0] + " " + parts[1], parts -> parts[2]));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
