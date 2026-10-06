package io.github.martiaaguilera.quantarun.controlplane.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** The worker protocol's execution calls over HTTP: claim, heartbeat with lease renewal, and fenced reports. */
@IntegrationTest
class WorkerExecutionApiTest {

    @Autowired
    MockMvc mvc;

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

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    @Test
    void claim_startsTheAssignedAttemptAndHandsOverItsPayload() throws Exception {
        var worker = fixture.worker("claimer", 2);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);

        claim(worker, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(1))
                .andExpect(jsonPath("$.assignments[0].attemptId").value(attempt.toString()))
                .andExpect(jsonPath("$.assignments[0].jobId").value(job.toString()))
                .andExpect(jsonPath("$.assignments[0].attemptNo").value(1))
                .andExpect(jsonPath("$.assignments[0].workloadType").value("delay"))
                .andExpect(jsonPath("$.assignments[0].payload.durationMs").value(10))
                .andExpect(jsonPath("$.assignments[0].timeoutSeconds").value(60));

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("RUNNING");
        assertThat(fixture.jobStatus(job)).isEqualTo("RUNNING");
        assertThat(fixture.eventCount(job, "STARTED")).isEqualTo(1);
        // A second claim finds nothing: the attempt is already running.
        claim(worker, 2).andExpect(jsonPath("$.assignments.length()").value(0));
    }

    @Test
    void report_onAnAttemptThatWasNeverClaimed_isRefused() throws Exception {
        var worker = fixture.worker("eager", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);

        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_CLAIMED"));
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ASSIGNED");
    }

    @Test
    void claim_neverHandsOutAnotherWorkersAssignment() throws Exception {
        var owner = fixture.worker("owner", 1);
        var job = fixture.submit(3);
        fixture.place();
        var other = fixture.worker("other", 1);

        claim(other, 1).andExpect(jsonPath("$.assignments.length()").value(0));
        assertThat(fixture.jobStatus(job)).isEqualTo("SCHEDULED");
        claim(owner, 1).andExpect(jsonPath("$.assignments.length()").value(1));
    }

    @Test
    void drainingWorker_stillClaimsWhatWasPlacedOnItBeforeTheDrain() throws Exception {
        var worker = fixture.worker("drainer", 1);
        var job = fixture.submit(3);
        fixture.place();
        mvc.perform(post("/api/v1/workers/" + worker.id() + "/drain")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk());

        claim(worker, 1).andExpect(jsonPath("$.assignments.length()").value(1));
        assertThat(fixture.jobStatus(job)).isEqualTo("RUNNING");
    }

    @Test
    void report_success_finishesTheJobAndReleasesTheReservation() throws Exception {
        var worker = fixture.worker("reporter", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);

        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\",\"result\":{\"tokens\":42}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.jobStatus").value("SUCCEEDED"));

        assertThat(fixture.jobStatus(job)).isEqualTo("SUCCEEDED");
        assertThat(fixture.slotsReserved(worker.id())).isZero();
        mvc.perform(get("/api/v1/jobs/" + job + "/attempts")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].result.tokens").value(42))
                .andExpect(jsonPath("$[0].retryDecision").value("succeeded"));
    }

    @Test
    void report_duplicateIsAnsweredIdempotently_aDifferentOutcomeIsRejected() throws Exception {
        var worker = fixture.worker("repeater", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);

        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}").andExpect(status().isOk());
        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobStatus").value("SUCCEEDED"));
        report(worker, attempt, "{\"outcome\":\"FAILED\",\"failureClass\":\"TRANSIENT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ACTIVE"));

        assertThat(fixture.eventCount(job, "SUCCEEDED")).isEqualTo(1);
        assertThat(fixture.slotsReserved(worker.id())).isZero();
    }

    @Test
    void report_afterLeaseRecovery_isFencedWith409() throws Exception {
        var worker = fixture.worker("partitioned", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);

        fixture.expireLease(attempt);
        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);

        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ACTIVE"));
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
        assertThat(fixture.eventCount(job, "SUCCEEDED")).isZero();
    }

    /**
     * Between a lease's expiry and the reaper's next pass, the attempt is still RUNNING in the table, but the heartbeat
     * already calls it lost. The report must agree with the heartbeat, or a worker could finish work it was told to
     * abandon. Accepting it was harmless for I6, yet it gave two answers to who owns the attempt (found by
     * ConcurrencyTortureTest).
     */
    @Test
    void report_afterLeaseExpiry_beforeRecovery_isFencedWith409() throws Exception {
        var worker = fixture.worker("slow", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);
        fixture.expireLease(attempt);

        assertThat(attempts.renewLeases(worker.id(), List.of(attempt)).lost()).containsExactly(attempt);
        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_EXPIRED"));

        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(job)).isEqualTo("RETRY_WAIT");
        assertThat(fixture.eventCount(job, "SUCCEEDED")).isZero();
    }

    @Test
    void report_forAnotherWorkersAttempt_isNotFound() throws Exception {
        var owner = fixture.worker("owner", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(owner, 1);
        var intruder = fixture.worker("intruder", 1);

        report(intruder, attempt, "{\"outcome\":\"SUCCEEDED\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
        report(owner, UUID.randomUUID(), "{\"outcome\":\"SUCCEEDED\"}").andExpect(status().isNotFound());
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("RUNNING");
    }

    @Test
    void report_withInconsistentFailureClass_isRejectedBeforeTouchingTheAttempt() throws Exception {
        var worker = fixture.worker("sloppy", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);

        report(worker, attempt, "{\"outcome\":\"FAILED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT"));
        report(worker, attempt, "{\"outcome\":\"FAILED\",\"failureClass\":\"WORKER_LOST\"}")
                .andExpect(status().isBadRequest());
        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\",\"failureClass\":\"TIMEOUT\"}")
                .andExpect(status().isBadRequest());
        report(worker, attempt, "{\"outcome\":\"EXPLODED\"}").andExpect(status().isBadRequest());
        report(worker, attempt, "{\"outcome\":\"FAILED\",\"failureClass\":\"TRANSIENT\",\"retryAfterMillis\":5}")
                .andExpect(status().isBadRequest());

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("RUNNING");
    }

    /**
     * Found by the STOP_CLAIMING chaos fault: a worker that kept heartbeating but never claimed held its assignments
     * forever, because heartbeats renewed every unclaimed one. Now only assignments younger than the claim timeout are
     * renewed; an older one is left to expire, and the job moves to another worker.
     */
    @Test
    void heartbeat_stopsRenewingAnAssignmentLeftUnclaimedPastTheClaimTimeout() throws Exception {
        var stuck = fixture.worker("stuck", 2);
        var fresh = fixture.submit(3);
        var stale = fixture.submit(3);
        fixture.place();
        var freshAttempt = fixture.latestAttempt(fresh);
        var staleAttempt = fixture.latestAttempt(stale);
        jdbc.sql("UPDATE job_attempts SET assigned_at = now() - interval '31 seconds' WHERE id = :id")
                .param("id", staleAttempt)
                .update();

        heartbeat(stuck).andExpect(status().isOk());

        assertThat(renewals(freshAttempt)).isEqualTo(1);
        assertThat(renewals(staleAttempt)).isZero();
        fixture.expireLease(staleAttempt);
        assertThat(attempts.recoverExpiredLeases(10)).isEqualTo(1);
        assertThat(fixture.attemptStatus(staleAttempt)).isEqualTo("LOST");
        assertThat(fixture.jobStatus(stale)).isEqualTo("RETRY_WAIT");
        assertThat(fixture.attemptStatus(freshAttempt)).isEqualTo("ASSIGNED");
    }

    @Test
    void heartbeat_renewsReportedLeases_andListsLostAndCancelledAttempts() throws Exception {
        var worker = fixture.worker("beater", 3);
        var kept = fixture.submit(3);
        var cancelled = fixture.submit(3);
        var lost = fixture.submit(3);
        fixture.place();
        claim(worker, 3);
        var keptAttempt = fixture.latestAttempt(kept);
        var cancelledAttempt = fixture.latestAttempt(cancelled);
        var lostAttempt = fixture.latestAttempt(lost);
        var leaseBefore = leaseOf(keptAttempt);

        lifecycle.cancel(cancelled);
        fixture.expireLease(lostAttempt);
        attempts.recoverExpiredLeases(10);

        heartbeat(worker, keptAttempt, cancelledAttempt, lostAttempt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.cancelAttemptIds.length()").value(1))
                .andExpect(jsonPath("$.cancelAttemptIds[0]").value(cancelledAttempt.toString()))
                .andExpect(jsonPath("$.lostAttemptIds.length()").value(1))
                .andExpect(jsonPath("$.lostAttemptIds[0]").value(lostAttempt.toString()));
        assertThat(leaseOf(keptAttempt)).isAfterOrEqualTo(leaseBefore);
        assertThat(renewals(keptAttempt)).isEqualTo(1);

        report(worker, cancelledAttempt, "{\"outcome\":\"CANCELLED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobStatus").value("CANCELLED"));
    }

    @Test
    void heartbeat_omittingARunningAttempt_letsItsLeaseExpire() throws Exception {
        var worker = fixture.worker("forgetful", 1);
        var job = fixture.submit(3);
        fixture.place();
        var attempt = fixture.latestAttempt(job);
        claim(worker, 1);

        heartbeat(worker).andExpect(jsonPath("$.lostAttemptIds.length()").value(0));

        // The worker no longer reports the attempt (it crashed and restarted its executor), so it is not renewed.
        assertThat(renewals(attempt)).isZero();
    }

    @Test
    void retiredWorker_cannotClaimRenewOrReport() throws Exception {
        var worker = fixture.worker("zombie", 2);
        var running = fixture.submit(3);
        fixture.place();
        claim(worker, 2);
        var attempt = fixture.latestAttempt(running);
        var assigned = fixture.submit(3);
        fixture.place();
        fixture.retire(worker.id());

        claim(worker, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKER_NOT_ACTIVE"));
        heartbeat(worker, attempt).andExpect(status().isConflict());
        report(worker, attempt, "{\"outcome\":\"SUCCEEDED\"}").andExpect(status().isConflict());

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("RUNNING");
        assertThat(fixture.jobStatus(assigned)).isEqualTo("SCHEDULED");
        assertThat(renewals(attempt)).isZero();
    }

    @Test
    void executionCalls_requireAWorkerCredential() throws Exception {
        mvc.perform(post(WorkerProtocol.BASE_PATH + "/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + IntegrationTest.WORKER_BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAssignments\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(WorkerProtocol.BASE_PATH + "/attempts/" + UUID.randomUUID() + "/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** A waiting claim with nothing placed answers empty once its wait is over, not at once. */
    @Test
    void aWaitingClaim_withNothingPlaced_answersEmptyAfterItsWait() throws Exception {
        var worker = fixture.worker("patient", 1);

        var started = System.nanoTime();
        waitingClaim(worker, 1, 400)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(0));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    /**
     * A placement that commits while a claim waits answers that claim at once: the assignment reaches the worker
     * without waiting for a poll. The wait here is 5 s; the answer must come long before.
     */
    @Test
    void aWaitingClaim_isAnsweredAsSoonAsWorkIsPlacedOnItsWorker() throws Exception {
        var worker = fixture.worker("eager", 1);
        var job = fixture.submit(3);
        var answer = new CompletableFuture<ResultActions>();
        var started = System.nanoTime();
        Thread.ofVirtual().start(() -> {
            try {
                answer.complete(waitingClaim(worker, 1, 5_000));
            } catch (Exception e) {
                answer.completeExceptionally(e);
            }
        });
        // Let the claim find nothing and start waiting before the job is placed.
        Thread.sleep(200);
        fixture.place();

        answer.get(4, TimeUnit.SECONDS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].jobId").value(job.toString()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private ResultActions waitingClaim(ExecutionFixture.RegisteredWorker worker, int max, int waitMillis)
            throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/claim")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAssignments\":" + max + ",\"waitMillis\":" + waitMillis + "}"));
    }

    private ResultActions claim(ExecutionFixture.RegisteredWorker worker, int max) throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/claim")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAssignments\":" + max + "}"));
    }

    private ResultActions report(ExecutionFixture.RegisteredWorker worker, UUID attempt, String body) throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/attempts/" + attempt + "/report")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions heartbeat(ExecutionFixture.RegisteredWorker worker, UUID... running) throws Exception {
        var ids = new StringBuilder();
        for (var id : running) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/heartbeat")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activeAttemptIds\":[" + ids + "]}"));
    }

    private java.time.Instant leaseOf(UUID attempt) {
        return jdbc.sql("SELECT lease_expires_at FROM job_attempts WHERE id = :id")
                .param("id", attempt)
                .query(java.time.Instant.class)
                .single();
    }

    private int renewals(UUID attempt) {
        return jdbc.sql("SELECT lease_renewals FROM job_attempts WHERE id = :id")
                .param("id", attempt)
                .query(Integer.class)
                .single();
    }
}
