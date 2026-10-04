package io.github.martiaaguilera.quantarun.controlplane.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A deployment that did not turn chaos on: nothing can be created, and nothing is delivered. */
class ChaosExperimentsTest {

    // No repository or query is reached when chaos is off; null collaborators would fail loudly if one were.
    private final ChaosExperiments disabled =
            new ChaosExperiments(null, new ChaosProperties(false, Duration.ofSeconds(30), 5), null, null);

    @Test
    void creatingAnExperiment_isForbidden() {
        assertThatThrownBy(() -> disabled.create(
                        new Caller.Admin(),
                        ChaosFault.KILL_WORKER,
                        new ChaosExperiments.Target(UUID.randomUUID(), null),
                        new FaultParameters(0, 0, 0, 0, 0)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("CHAOS_DISABLED"));
    }

    @Test
    void heartbeatsCarryNoFaults() {
        assertThat(disabled.deliver(UUID.randomUUID())).isEmpty();
    }
}
