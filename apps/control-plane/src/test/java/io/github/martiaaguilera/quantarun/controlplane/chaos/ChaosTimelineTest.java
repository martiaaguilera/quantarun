package io.github.martiaaguilera.quantarun.controlplane.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEvent;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobEventType;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.ChaosFault;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChaosTimelineTest {

    private static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID worker = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();
    private long eventIds;

    @Test
    void aLostAttemptThatSucceedsElsewhere_isDisruptedAndRecovered_measuredFromWhenTheFaultFired() {
        var kill = experiment(ChaosFault.KILL_WORKER, new FaultParameters(2_000, 0, 0, 0, 0));
        var events = List.of(
                event(JobEventType.STARTED, -5_000),
                event(JobEventType.ATTEMPT_LOST, 19_000),
                event(JobEventType.RETRY_SCHEDULED, 19_000),
                event(JobEventType.SCHEDULED, 20_500),
                event(JobEventType.SUCCEEDED, 23_000));

        var timeline = ChaosTimeline.build(
                kill, null, List.of(new ChaosTimeline.AffectedJob(job, "SUCCEEDED", events)), List.of());

        var recovery = timeline.jobs().getFirst();
        // The kill fired 2 s after delivery; detection is lease expiry, 17 s later.
        assertThat(recovery.detectionMs()).isEqualTo(17_000);
        assertThat(recovery.recoveryMs()).isEqualTo(21_000);
        assertThat(timeline.summary()).isEqualTo(new ChaosTimeline.Summary(1, 1, 1, 21_000L));
        // Events from before delivery are context, not part of the fault's story.
        assertThat(timeline.entries())
                .extracting(ChaosTimeline.Entry::type)
                .containsExactly(
                        "CREATED", "DELIVERED", "FIRES", "ATTEMPT_LOST", "RETRY_SCHEDULED", "SCHEDULED", "SUCCEEDED");
    }

    @Test
    void aJobThatWasNeverDisrupted_isAffectedButNotRecovered() {
        var stall = experiment(ChaosFault.STALL_ATTEMPTS, new FaultParameters(0, 0, 1, 0, 0));

        var timeline = ChaosTimeline.build(
                stall,
                null,
                List.of(new ChaosTimeline.AffectedJob(job, "SUCCEEDED", List.of(event(JobEventType.SUCCEEDED, 1_000)))),
                List.of());

        assertThat(timeline.jobs().getFirst().disruptedAt()).isNull();
        assertThat(timeline.jobs().getFirst().recoveredAt()).isNull();
        assertThat(timeline.summary()).isEqualTo(new ChaosTimeline.Summary(1, 0, 0, null));
    }

    @Test
    void aJobStillFailing_isDisruptedButNotRecovered() {
        var errors = experiment(ChaosFault.PROVIDER_ERROR, new FaultParameters(0, 0, 3, 0, 0));
        var events = new ArrayList<JobEvent>();
        events.add(event(JobEventType.ATTEMPT_FAILED, 500));
        events.add(event(JobEventType.RETRY_SCHEDULED, 500));

        var timeline = ChaosTimeline.build(
                errors, null, List.of(new ChaosTimeline.AffectedJob(job, "RETRY_WAIT", events)), List.of());

        assertThat(timeline.jobs().getFirst().detectionMs()).isEqualTo(500);
        assertThat(timeline.jobs().getFirst().recoveryMs()).isNull();
        assertThat(timeline.summary()).isEqualTo(new ChaosTimeline.Summary(1, 1, 0, null));
    }

    @Test
    void anUndeliveredExperiment_hasNoJobStory() {
        var expired = new ChaosExperiment(
                UUID.randomUUID(),
                ChaosFault.PAUSE_HEARTBEAT,
                worker,
                null,
                new FaultParameters(0, 30_000, 0, 0, 0),
                ChaosStatus.EXPIRED,
                T0,
                T0.plusSeconds(30),
                null,
                T0.plusSeconds(31));

        var timeline = ChaosTimeline.build(expired, null, List.of(), List.of());

        assertThat(timeline.entries()).extracting(ChaosTimeline.Entry::type).containsExactly("CREATED", "EXPIRED");
        assertThat(timeline.jobs()).isEmpty();
    }

    private ChaosExperiment experiment(ChaosFault fault, FaultParameters parameters) {
        return new ChaosExperiment(
                UUID.randomUUID(),
                fault,
                worker,
                job,
                parameters,
                ChaosStatus.DELIVERED,
                T0.minusSeconds(1),
                T0.plusSeconds(29),
                T0,
                null);
    }

    private JobEvent event(JobEventType type, long millisAfterDelivery) {
        return new JobEvent(
                ++eventIds, job, UUID.randomUUID(), type, T0.plusMillis(millisAfterDelivery), JSON.createObjectNode());
    }
}
