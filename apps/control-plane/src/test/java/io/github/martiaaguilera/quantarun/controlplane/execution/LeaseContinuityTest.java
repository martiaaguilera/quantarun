package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.SettableClock;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts.ReportResult;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.LeaseContinuity;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live PostgreSQL outage of 2026-10-04, second round: with the reaper already catching up after a gap, the first
 * report to arrive after the database came back still beat the reaper's next tick, met an expired lease and was
 * refused, and the finished work ran again. Whoever reaches the database first after a gap now extends the leases.
 */
@IntegrationTest
class LeaseContinuityTest {

    private static final Duration DEAF_AFTER = Duration.ofSeconds(6);

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
    final SettableClock clock = new SettableClock(Instant.parse("2026-10-04T12:00:00Z"));
    LeaseContinuity continuity;
    ExecutionFixture.RegisteredWorker worker;
    UUID job;
    UUID attempt;

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        continuity = new LeaseContinuity(attempts, clock, DEAF_AFTER, true);
        worker = fixture.worker("survivor", 1);
        job = fixture.submit(3);
        fixture.place();
        attempt = fixture.latestAttempt(job);
        attempts.claim(worker.id(), 1);
        continuity.catchUp();
    }

    @Test
    void theFirstReportAfterAGap_isJudgedOnExtendedLeases() {
        clock.set(clock.instant().plusSeconds(40));
        fixture.expireLease(attempt);

        continuity.catchUp();
        var report = attempts.report(worker.id(), attempt, AttemptOutcome.SUCCEEDED, null, null, null, null);

        assertThat(report).isInstanceOf(ReportResult.Applied.class);
        assertThat(fixture.jobStatus(job)).isEqualTo("SUCCEEDED");
    }

    @Test
    void theFirstHeartbeatAfterAGap_renewsInsteadOfCallingTheAttemptLost() {
        clock.set(clock.instant().plusSeconds(40));
        fixture.expireLease(attempt);

        continuity.catchUp();

        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).renewedRunning())
                .containsExactly(attempt);
    }

    /** Never having heard is a gap too: a restarted control plane extends leases before its first request judges one. */
    @Test
    void aFreshInstance_extendsBeforeJudgingAnyLease() {
        fixture.expireLease(attempt);

        new LeaseContinuity(attempts, clock, DEAF_AFTER, true).catchUp();

        assertThat(attempts.report(worker.id(), attempt, AttemptOutcome.SUCCEEDED, null, null, null, null))
                .isInstanceOf(ReportResult.Applied.class);
    }

    /** Without a gap nothing is extended: a lease that runs out while the control plane listens is a real loss. */
    @Test
    void withoutAGap_anExpiredLeaseStaysExpired() {
        clock.set(clock.instant().plusSeconds(2));
        fixture.expireLease(attempt);

        continuity.catchUp();

        assertThat(attempts.report(worker.id(), attempt, AttemptOutcome.SUCCEEDED, null, null, null, null))
                .isInstanceOf(ReportResult.LeaseExpired.class);
    }
}
