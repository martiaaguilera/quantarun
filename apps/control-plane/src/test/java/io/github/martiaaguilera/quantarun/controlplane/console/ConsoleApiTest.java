package io.github.martiaaguilera.quantarun.controlplane.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.execution.ExecutionFixture;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.SchedulingCycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerRegistry;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.AttemptOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The endpoints the console needs beyond the resource APIs: the overview, job filters and the effective settings. */
@IntegrationTest
class ConsoleApiTest {

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
    ApiTestSupport support;

    @BeforeEach
    void setUp() {
        fixture = new ExecutionFixture(jdbc, registry, lifecycle, cycle, json);
        fixture.reset();
        support = new ApiTestSupport(mvc, json);
    }

    @Test
    void overview_countsJobsForTheCaller_andShowsTheFleetToOperatorsOnly() throws Exception {
        var worker = fixture.worker("overview", 2);
        var mine = support.createProject();
        var other = support.createProject();
        var succeeded = submit(mine, "delay", 4);
        submit(mine, "delay", 4);
        submit(mine, "delay", 4);
        submit(other, "delay", 4);
        fixture.place();
        var claimed = attempts.claim(worker.id(), 2);
        attempts.report(
                worker.id(), claimedAttemptOf(claimed, succeeded), AttemptOutcome.SUCCEEDED, null, null, null, null);

        var asMember = body(get(mine, "/api/v1/overview").andExpect(status().isOk()));
        assertThat(asMember.at("/jobs/byStatus/SUCCEEDED").asLong()).isEqualTo(1);
        assertThat(asMember.at("/jobs/queued").asLong()
                        + asMember.at("/jobs/running").asLong())
                .isEqualTo(2);
        assertThat(asMember.at("/jobs/successRate").asDouble()).isEqualTo(1.0);
        assertThat(asMember.at("/jobs/timeToStartP95Seconds").isNumber()).isTrue();
        assertThat(asMember.get("fleet").isNull()).isTrue();

        var asAdmin = body(getAsAdmin("/api/v1/overview").andExpect(status().isOk()));
        assertThat(asAdmin.at("/jobs/byStatus/SUCCEEDED").asLong()).isEqualTo(1);
        assertThat(asAdmin.at("/jobs/queued").asLong()
                        + asAdmin.at("/jobs/running").asLong())
                .isEqualTo(3);
        assertThat(asAdmin.at("/fleet/healthyWorkers").asInt()).isEqualTo(1);
        assertThat(asAdmin.at("/fleet/capacity/slots").asInt()).isEqualTo(2);
        assertThat(asAdmin.at("/fleet/reserved/slots").asInt()).isEqualTo(1);
        assertThat(asAdmin.at("/fleet/utilization/slots").asDouble()).isEqualTo(0.5);
    }

    @Test
    void jobs_canBeFilteredByWorkloadPriorityWorkerAndTime() throws Exception {
        var worker = fixture.worker("filtered", 1);
        var project = support.createProject();
        var cpuHash = submit(project, "cpu-hash", 7);
        var delay = submit(project, "delay", 2);
        fixture.place();
        var ranOnWorker = jdbc.sql("SELECT job_id FROM job_attempts WHERE worker_id = :w")
                .param("w", worker.id())
                .query(UUID.class)
                .single();

        assertThat(ids(project, "workloadType=cpu-hash")).containsExactly(cpuHash.toString());
        assertThat(ids(project, "priority=2")).containsExactly(delay.toString());
        assertThat(ids(project, "workerId=" + worker.id())).containsExactly(ranOnWorker.toString());
        assertThat(ids(project, "createdFrom=" + Instant.now().plusSeconds(60))).isEmpty();
        assertThat(ids(project, "createdTo=" + Instant.now().plusSeconds(60))).hasSize(2);
        get(project, "/api/v1/jobs?workloadType=quantum").andExpect(status().isBadRequest());
    }

    @Test
    void settings_showTheEffectiveConfiguration_toOperatorsOnly() throws Exception {
        getAsAdmin("/api/v1/settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduler.policy").isString())
                .andExpect(jsonPath("$.workers.leaseDuration").exists())
                .andExpect(jsonPath("$.workers.claimTimeout").exists())
                .andExpect(jsonPath("$.retries.maxDelay").exists())
                .andExpect(jsonPath("$.chaos.enabled").value(true));
        get(support.createProject(), "/api/v1/settings").andExpect(status().isForbidden());
    }

    @Test
    void session_saysWhoTheCredentialBelongsTo() throws Exception {
        var project = support.createProject();
        getAsAdmin("/api/v1/session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.projectId").doesNotExist());
        get(project, "/api/v1/session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PROJECT"))
                .andExpect(jsonPath("$.projectId").value(project.id().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/session"))
                .andExpect(status().isUnauthorized());
    }

    private UUID claimedAttemptOf(java.util.List<JobAttempts.Claimed> claimed, UUID job) {
        return claimed.stream()
                .filter(attempt -> attempt.jobId().equals(job))
                .findFirst()
                .orElseThrow()
                .attemptId();
    }

    private UUID submit(ApiTestSupport.TestProject project, String workload, int priority) throws Exception {
        var payload = workload.equals("cpu-hash") ? "{\"iterations\":10}" : "{\"durationMs\":10}";
        var response = mvc.perform(post("/api/v1/jobs")
                        .header(HttpHeaders.AUTHORIZATION, project.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workloadType":"%s","payload":%s,"priority":%d,
                                 "resources":{"cpuMillis":500,"memoryMib":256}}""".formatted(workload, payload, priority)))
                .andExpect(status().isCreated());
        return UUID.fromString(body(response).get("id").asString());
    }

    private java.util.List<String> ids(ApiTestSupport.TestProject project, String query) throws Exception {
        var page = body(get(project, "/api/v1/jobs?" + query).andExpect(status().isOk()));
        var ids = new ArrayList<String>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private ResultActions get(ApiTestSupport.TestProject project, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, project.bearer()));
    }

    private ResultActions getAsAdmin(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
