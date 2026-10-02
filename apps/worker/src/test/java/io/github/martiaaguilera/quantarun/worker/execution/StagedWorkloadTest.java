package io.github.martiaaguilera.quantarun.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The staged workload commits after every stage, resumes after the last commit, and ends with the same digest. */
class StagedWorkloadTest {

    private final StagedWorkload staged = new StagedWorkload();
    private final Payload threeStages = new Payload(Map.of(
            "stages",
            List.of(
                    Map.of("name", "fetch", "durationMs", 1),
                    Map.of("name", "embed", "durationMs", 1),
                    Map.of("name", "index", "durationMs", 1))));

    record Commit(int stage, Map<String, Object> result) {}

    @Test
    void fromZero_commitsEveryStageInOrder() throws Exception {
        var commits = new ArrayList<Commit>();

        var result = staged.execute(threeStages, context(1, null, commits));

        assertThat(commits).extracting(Commit::stage).containsExactly(0, 1, 2);
        assertThat(result)
                .containsEntry("stages", 3)
                .containsEntry("resumedAfterStage", -1)
                .containsEntry("executedStages", 3);
    }

    @Test
    void afterAFailure_resumesAfterTheLastCommit_andReachesTheSameDigest() throws Exception {
        var failing = new Payload(
                Map.of("stages", threeStages.values().get("stages"), "failAtStage", 2, "failOnAttempts", 1));
        var firstCommits = new ArrayList<Commit>();

        assertThatThrownBy(() -> staged.execute(failing, context(1, null, firstCommits)))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.TRANSIENT));
        assertThat(firstCommits).extracting(Commit::stage).containsExactly(0, 1);

        var last = firstCommits.getLast();
        var resumedCommits = new ArrayList<Commit>();
        var resumed = staged.execute(
                failing, context(2, new WorkerProtocol.Checkpoint(last.stage(), last.result()), resumedCommits));

        assertThat(resumedCommits).extracting(Commit::stage).containsExactly(2);
        assertThat(resumed).containsEntry("resumedAfterStage", 1).containsEntry("executedStages", 1);
        var uninterrupted = staged.execute(threeStages, context(1, null, new ArrayList<>()));
        assertThat(resumed.get("digest"))
                .as("resuming skips no stage and repeats none")
                .isEqualTo(uninterrupted.get("digest"));
    }

    @Test
    void aFencedCheckpoint_stopsTheWorkloadAtThatStage() {
        var commits = new ArrayList<Commit>();
        var context = new AttemptContext(1, null, (stage, result) -> {
            if (stage == 1) {
                throw new AttemptFencedException("recovered");
            }
            commits.add(new Commit(stage, result));
        });

        assertThatThrownBy(() -> staged.execute(threeStages, context)).isInstanceOf(AttemptFencedException.class);
        assertThat(commits).extracting(Commit::stage).containsExactly(0);
    }

    @Test
    void rejectsMalformedStageLists() {
        assertInvalid(new Payload(Map.of()));
        assertInvalid(new Payload(Map.of("stages", List.of())));
        assertInvalid(new Payload(Map.of("stages", List.of("not an object"))));
        assertInvalid(new Payload(Map.of("stages", List.of(Map.of("name", "no duration")))));
        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i <= StagedWorkload.MAX_STAGES; i++) {
            tooMany.add(Map.of("durationMs", 0));
        }
        assertInvalid(new Payload(Map.of("stages", tooMany)));
    }

    @Test
    void memoryWorkload_holdsAndReleasesTheRequestedMemory() throws Exception {
        var result = new MemoryWorkload()
                .execute(new Payload(Map.of("mib", 4, "holdMs", 1)), AttemptContext.withoutCheckpoints(1));

        assertThat(result).containsEntry("allocatedMib", 4).containsEntry("heldMs", 1L);
        assertThatThrownBy(() -> new MemoryWorkload()
                        .execute(new Payload(Map.of("mib", 4096, "holdMs", 1)), AttemptContext.withoutCheckpoints(1)))
                .isInstanceOf(WorkloadFailure.class);
    }

    private void assertInvalid(Payload payload) {
        assertThatThrownBy(() -> staged.execute(payload, context(1, null, new ArrayList<>())))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.INVALID_INPUT));
    }

    private static AttemptContext context(int attemptNo, WorkerProtocol.Checkpoint last, List<Commit> commits) {
        return new AttemptContext(attemptNo, last, (stage, result) -> commits.add(new Commit(stage, result)));
    }
}
