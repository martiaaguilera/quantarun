package io.github.martiaaguilera.quantarun.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.execution.ExecutionFixture;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobGauges;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobPlacement;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.FleetGauges;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 10: one trace per job across submission, queueing, scheduling, execution and completion; metrics recorded only
 * for committed work; gauges that match the database.
 */
@IntegrationTest
class ObservabilityTest {

    private static final String DELAY_JOB = """
            {"workloadType":"delay","payload":{"durationMs":10},"maxAttempts":3,
             "resources":{"cpuMillis":500,"memoryMib":256}}""";

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

    @Autowired
    JobPlacement placement;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JobGauges jobGauges;

    @Autowired
    FleetGauges fleetGauges;

    @Autowired
    CollectedSpans.Exporter spans;

    ExecutionFixture fixture;
    ApiTestSupport.TestProject project;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        project = new ApiTestSupport(mvc, json).createProject();
    }

    @AfterEach
    void reservationsStayConsistent() {
        fixture.assertReservationsMatchActiveAttempts();
    }

    /**
     * The client's trace context comes in with the submission; the job keeps it, placement adds the queued and
     * scheduling spans to it, the claim hands the scheduling span's context to the worker, and the worker's report
     * (which carries that context, as the worker's HTTP client does) lands in the same trace.
     */
    @Test
    void aJobsWholeLife_isOneTrace() throws Exception {
        var worker = fixture.worker("traced", 1);
        var traceId = randomHex(16);
        var clientSpan = randomHex(8);

        var submitted = body(submit("00-" + traceId + "-" + clientSpan + "-01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.traceId").value(traceId)));
        var jobId = UUID.fromString(submitted.get("id").asString());
        var jobContext = column("SELECT trace_parent FROM jobs WHERE id = :id", jobId);
        assertThat(jobContext).startsWith("00-" + traceId + "-").doesNotContain(clientSpan);

        fixture.place();
        var attemptId = fixture.latestAttempt(jobId);
        var attemptContext = column("SELECT trace_parent FROM job_attempts WHERE id = :id", attemptId);
        assertThat(attemptContext).startsWith("00-" + traceId + "-").isNotEqualTo(jobContext);

        var assignment = body(claim(worker)).at("/assignments/0");
        assertThat(assignment.get("traceParent").asString()).isEqualTo(attemptContext);
        report(worker, attemptId, attemptContext, "{\"outcome\":\"SUCCEEDED\"}").andExpect(status().isOk());

        var trace = spans.trace(traceId);
        var submissionSpan = spanIdOf(jobContext);
        var scheduleSpan = spanIdOf(attemptContext);
        var schedule = named(trace, "job.schedule");
        assertThat(schedule.getSpanId()).isEqualTo(scheduleSpan);
        assertThat(schedule.getParentSpanId()).isEqualTo(submissionSpan);
        assertThat(schedule.getAttributes().asMap().toString())
                .contains("quantarun.worker.id=" + worker.id(), "quantarun.attempt.no=1");
        var queued = named(trace, "job.queued");
        assertThat(queued.getParentSpanId()).isEqualTo(submissionSpan);
        assertThat(queued.getEndEpochNanos()).isGreaterThanOrEqualTo(queued.getStartEpochNanos());
        // The report's server span is a child of the scheduling decision: execution and completion join the trace.
        assertThat(trace).anySatisfy(span -> {
            assertThat(span.getParentSpanId()).isEqualTo(scheduleSpan);
            assertThat(span.getName()).contains("report");
        });
    }

    /** The reaper's recovery of a lost attempt is visible in the job's trace too. */
    @Test
    void aLostAttempt_isMarkedInTheTrace() throws Exception {
        fixture.worker("vanishing", 1);
        var traceId = randomHex(16);
        var jobId = UUID.fromString(body(submit("00-" + traceId + "-" + randomHex(8) + "-01"))
                .get("id")
                .asString());
        fixture.place();
        var attemptId = fixture.latestAttempt(jobId);
        var attemptContext = column("SELECT trace_parent FROM job_attempts WHERE id = :id", attemptId);

        fixture.expireLease(attemptId);
        attempts.recoverExpiredLeases(10);

        var lost = named(spans.trace(traceId), "attempt.lost");
        assertThat(lost.getParentSpanId()).isEqualTo(spanIdOf(attemptContext));
    }

    /** A placement that rolls back leaves neither spans nor a counted placement behind. */
    @Test
    void aRolledBackPlacement_leavesNoSpans() throws Exception {
        var worker = fixture.worker("rollback", 1);
        var traceId = randomHex(16);
        var jobId = UUID.fromString(body(submit("00-" + traceId + "-" + randomHex(8) + "-01"))
                .get("id")
                .asString());

        transactions.executeWithoutResult(tx -> {
            var job = placement.lockRunnableJobs(10, JobPlacement.WindowOrder.OLDEST_FIRST).stream()
                    .filter(candidate -> candidate.id().equals(jobId))
                    .findFirst()
                    .orElseThrow();
            placement.assignAttempt(job, worker.id(), "test placement", Duration.ofSeconds(15));
            tx.setRollbackOnly();
        });

        assertThat(fixture.jobStatus(jobId)).isEqualTo("QUEUED");
        assertThat(spans.trace(traceId)).extracting(SpanData::getName).doesNotContain("job.schedule", "job.queued");
    }

    @Test
    void jobMetrics_countCommittedWorkOnly() throws Exception {
        var worker = fixture.worker("metered", 4);
        var submittedBefore = counter("quantarun.jobs.submitted", "workload_type", "delay");
        var succeededBefore = counter("quantarun.jobs.finished", "workload_type", "delay", "status", "SUCCEEDED");
        var retriedBefore = counter("quantarun.attempts.ended", "workload_type", "delay", "decision", "retry");
        var lostBefore = counter("quantarun.leases.expired");
        var cancelledBefore = counter("quantarun.jobs.finished", "workload_type", "delay", "status", "CANCELLED");
        var missedBefore = counter("quantarun.jobs.deadline.missed", "workload_type", "delay");
        var waitsBefore = timerCount("quantarun.jobs.queue.wait", "workload_type", "delay");
        var executionsBefore =
                timerCount("quantarun.attempts.execution", "workload_type", "delay", "outcome", "SUCCEEDED");

        // Two submissions with one idempotency key: one job, one count.
        var succeeding =
                UUID.fromString(body(submitWithKey("same-key")).get("id").asString());
        submitWithKey("same-key").andExpect(status().isOk());
        var failing = UUID.fromString(body(submit(null)).get("id").asString());
        var vanishing = UUID.fromString(body(submit(null)).get("id").asString());
        var late = UUID.fromString(body(submit(null)).get("id").asString());
        var cancelled = UUID.fromString(body(submit(null)).get("id").asString());
        // Deadlines in the past cannot be submitted, so this one is moved after the fact.
        jdbc.sql("UPDATE jobs SET deadline_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", late)
                .update();
        lifecycle.cancel(cancelled);
        assertThat(counter("quantarun.jobs.submitted", "workload_type", "delay"))
                .isEqualTo(submittedBefore + 5);

        fixture.place();
        claim(worker);
        report(worker, fixture.latestAttempt(succeeding), null, "{\"outcome\":\"SUCCEEDED\"}");
        report(worker, fixture.latestAttempt(late), null, "{\"outcome\":\"SUCCEEDED\"}");
        report(worker, fixture.latestAttempt(failing), null, "{\"outcome\":\"FAILED\",\"failureClass\":\"TRANSIENT\"}");
        fixture.expireLease(fixture.latestAttempt(vanishing));
        attempts.recoverExpiredLeases(10);

        assertThat(counter("quantarun.jobs.finished", "workload_type", "delay", "status", "SUCCEEDED"))
                .isEqualTo(succeededBefore + 2);
        assertThat(counter("quantarun.attempts.ended", "workload_type", "delay", "decision", "retry"))
                .isEqualTo(retriedBefore + 2);
        assertThat(counter("quantarun.leases.expired")).isEqualTo(lostBefore + 1);
        assertThat(counter("quantarun.jobs.finished", "workload_type", "delay", "status", "CANCELLED"))
                .isEqualTo(cancelledBefore + 1);
        // The late job succeeded after its deadline; the cancelled one missed nothing.
        assertThat(counter("quantarun.jobs.deadline.missed", "workload_type", "delay"))
                .isEqualTo(missedBefore + 1);
        assertThat(timerCount("quantarun.jobs.queue.wait", "workload_type", "delay"))
                .isEqualTo(waitsBefore + 4);
        assertThat(timerCount("quantarun.attempts.execution", "workload_type", "delay", "outcome", "SUCCEEDED"))
                .isEqualTo(executionsBefore + 2);
    }

    @Test
    void gauges_matchTheDatabase() throws Exception {
        fixture.worker("small", 2);
        fixture.worker("large", 6);
        for (int i = 0; i < 5; i++) {
            submit(null).andExpect(status().isCreated());
        }
        fixture.place();

        jobGauges.refresh();
        fleetGauges.refresh();

        assertThat(gauge("quantarun.jobs.active", "status", "SCHEDULED")).isEqualTo(5);
        assertThat(gauge("quantarun.jobs.active", "status", "QUEUED")).isZero();
        assertThat(gauge("quantarun.workers", "lifecycle", "ACTIVE")).isEqualTo(2);
        assertThat(gauge("quantarun.fleet.capacity", "resource", "slots")).isEqualTo(8);
        assertThat(gauge("quantarun.fleet.reserved", "resource", "slots")).isEqualTo(5);
        assertThat(gauge("quantarun.fleet.reserved", "resource", "cpu_millis")).isEqualTo(2_500);
    }

    private ResultActions submit(String traceParent) throws Exception {
        var request = post("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, project.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DELAY_JOB);
        if (traceParent != null) {
            request = request.header("traceparent", traceParent);
        }
        return mvc.perform(request);
    }

    private ResultActions submitWithKey(String key) throws Exception {
        return mvc.perform(post("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, project.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DELAY_JOB));
    }

    private ResultActions claim(ExecutionFixture.RegisteredWorker worker) throws Exception {
        return mvc.perform(post(WorkerProtocol.BASE_PATH + "/claim")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAssignments\":10}"));
    }

    private ResultActions report(
            ExecutionFixture.RegisteredWorker worker, UUID attemptId, String traceParent, String body)
            throws Exception {
        var request = post(WorkerProtocol.BASE_PATH + "/attempts/" + attemptId + "/report")
                .header(HttpHeaders.AUTHORIZATION, worker.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (traceParent != null) {
            request = request.header("traceparent", traceParent);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String column(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(String.class).single();
    }

    private double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counters();
        return counter.stream().mapToDouble(c -> c.count()).sum();
    }

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timers().stream()
                .mapToLong(t -> t.count())
                .sum();
    }

    private double gauge(String name, String... tags) {
        return meters.get(name).tags(tags).gauge().value();
    }

    private static SpanData named(List<SpanData> trace, String name) {
        return trace.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " span in "
                        + trace.stream().map(SpanData::getName).toList()));
    }

    private static String spanIdOf(String traceParent) {
        return traceParent.substring(36, 52);
    }

    private static String randomHex(int bytes) {
        var value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
