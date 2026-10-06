package io.github.martiaaguilera.quantarun.controlplane.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.SettableClock;
import io.github.martiaaguilera.quantarun.controlplane.execution.ExecutionFixture;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.LeaseContinuity;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The reaper as the background task runs it: armed only after the startup extension, then draining in batches. */
@IntegrationTest
class LeaseReaperTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    WorkerRegistry registry;

    @Autowired
    JobLifecycle lifecycle;

    @Autowired
    SchedulingCycle cycle;

    @Autowired
    JobAttempts attempts;

    ExecutionFixture fixture;

    static final Duration DEAF_AFTER = Duration.ofSeconds(6);
    final SettableClock clock = new SettableClock(Instant.parse("2026-10-04T12:00:00Z"));

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
    }

    @Test
    void afterARestart_leasesThatLookExpiredAreExtendedBeforeAnythingIsReaped() {
        var worker = fixture.worker("survivor", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);
        // Simulates control-plane downtime: nobody renewed the lease while it was down.
        fixture.expireLease(attempt);
        var reaper = new LeaseReaper(attempts, new LeaseContinuity(attempts, clock, DEAF_AFTER, true));

        reaper.reap();
        assertThat(fixture.attemptStatus(attempt)).as("not armed yet").isEqualTo("RUNNING");

        reaper.extendLeasesAfterStartup();
        reaper.reap();
        assertThat(fixture.attemptStatus(attempt))
                .as("extended, so still alive")
                .isEqualTo("RUNNING");

        // The worker really is gone now: once its lease runs out again, the armed reaper recovers it.
        fixture.expireLease(attempt);
        reaper.reap();
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
        fixture.assertReservationsMatchActiveAttempts();
    }

    @Test
    void oneTick_drainsMoreThanOneBatch() {
        var worker = fixture.worker("big", 200);
        var jobs = new ArrayList<UUID>();
        for (int i = 0; i < LeaseReaper.BATCH_SIZE * 2 + 5; i++) {
            jobs.add(fixture.submit(3));
        }
        fixture.place();
        var reaper = new LeaseReaper(attempts, new LeaseContinuity(attempts, clock, DEAF_AFTER, true));
        reaper.extendLeasesAfterStartup();
        jdbc.sql("UPDATE job_attempts SET lease_expires_at = now() - interval '1 second'")
                .update();

        reaper.reap();

        assertThat(fixture.slotsReserved(worker.id())).isZero();
        jobs.forEach(job -> assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT"));
        fixture.assertReservationsMatchActiveAttempts();
    }

    /**
     * The live PostgreSQL outage of 2026-10-04: no lease could be renewed while the database was down, so when it came
     * back the reaper declared every running attempt lost although every worker was alive. A gap between successful
     * ticks now extends the leases first, as a restart does.
     */
    @Test
    void afterAGapInHearing_leasesAreExtendedBeforeAnythingIsReaped() {
        var worker = fixture.worker("survivor", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);
        var reaper = new LeaseReaper(attempts, new LeaseContinuity(attempts, clock, DEAF_AFTER, true));
        reaper.extendLeasesAfterStartup();
        reaper.reap();

        // The database was unreachable for 40 s: every tick failed, and the lease ran out meanwhile.
        clock.set(clock.instant().plusSeconds(40));
        fixture.expireLease(attempt);
        reaper.reap();
        assertThat(fixture.attemptStatus(attempt)).as("extended, not reaped").isEqualTo("RUNNING");
        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).renewedRunning())
                .as("the worker's next heartbeat renews it as usual")
                .containsExactly(attempt);

        // Ticks are regular again, so a lease that runs out now is a real loss.
        clock.set(clock.instant().plusSeconds(1));
        fixture.expireLease(attempt);
        reaper.reap();
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
        fixture.assertReservationsMatchActiveAttempts();
    }
}
