package io.github.martiaaguilera.quantarun.controlplane.jobs;

import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.CANCELLED;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.DEAD;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.FAILED;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.QUEUED;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.RETRY_WAIT;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.RUNNING;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.SCHEDULED;
import static io.github.martiaaguilera.quantarun.controlplane.jobs.JobStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Invariant I5. The expected table is written out independently of the implementation, from docs/SPEC.md §3, and
 * every one of the 64 ordered pairs is checked, so an accidentally added or removed transition fails this test.
 */
class JobStatusTest {

    private static final Map<JobStatus, Set<JobStatus>> SPEC_TRANSITIONS = Map.of(
            QUEUED, EnumSet.of(SCHEDULED, CANCELLED),
            SCHEDULED, EnumSet.of(RUNNING, RETRY_WAIT, DEAD, CANCELLED),
            RUNNING, EnumSet.of(SUCCEEDED, RETRY_WAIT, FAILED, DEAD, CANCELLED),
            RETRY_WAIT, EnumSet.of(SCHEDULED, CANCELLED),
            DEAD, EnumSet.of(QUEUED),
            SUCCEEDED, EnumSet.noneOf(JobStatus.class),
            FAILED, EnumSet.noneOf(JobStatus.class),
            CANCELLED, EnumSet.noneOf(JobStatus.class));

    @Test
    void everyOrderedPair_matchesTheSpecificationTable() {
        assertThat(SPEC_TRANSITIONS).containsOnlyKeys(JobStatus.values());
        for (var from : JobStatus.values()) {
            for (var to : JobStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(SPEC_TRANSITIONS.get(from).contains(to));
            }
        }
    }

    @Test
    void illegalTransition_failsExplicitly() {
        assertThatThrownBy(() -> SUCCEEDED.requireTransitionTo(RUNNING))
                .isInstanceOf(IllegalJobTransitionException.class)
                .hasMessageContaining("SUCCEEDED -> RUNNING");
        assertThatThrownBy(() -> CANCELLED.requireTransitionTo(QUEUED))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    void noStateTransitionsToItself() {
        for (var status : JobStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .as("%s -> itself", status)
                    .isFalse();
        }
    }

    @Test
    void finalStates_onlyDeadCanBeRevived() {
        for (var status : JobStatus.values()) {
            if (status.isFinal() && status != DEAD) {
                assertThat(SPEC_TRANSITIONS.get(status))
                        .as("%s is terminal", status)
                        .isEmpty();
            }
        }
        assertThat(DEAD.canTransitionTo(QUEUED)).isTrue();
    }

    @Test
    void classificationHelpers_areConsistentWithTheTable() {
        for (var status : JobStatus.values()) {
            assertThat(status.isRunnable()).isEqualTo(status.canTransitionTo(SCHEDULED));
        }
        assertThat(EnumSet.allOf(JobStatus.class).stream().filter(JobStatus::hasActiveAttempt))
                .containsExactlyInAnyOrder(SCHEDULED, RUNNING);
    }
}
