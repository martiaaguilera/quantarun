package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SchedulerApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SchedulingCycle cycle;

    @BeforeEach
    void emptyQueueAndFleet() {
        jdbc.sql("TRUNCATE scheduler_decisions, job_events, job_attempts, jobs, worker_heartbeats, workers CASCADE")
                .update();
    }

    @Test
    void jobDecisions_explainThePlacementToItsOwnerOnly() throws Exception {
        var support = new ApiTestSupport(mvc, json);
        var owner = support.createProject();
        var stranger = support.createProject();
        registerWorker("gpu-node", "cuda");
        registerWorker("cpu-node", null);
        var body = json.readTree(mvc.perform(post("/api/v1/jobs")
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workloadType":"mock-inference","payload":{},"requiredLabels":["cuda"],
                                 "resources":{"cpuMillis":500,"memoryMib":256,"accelerators":1}}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var jobId = body.get("id").asString();

        cycle.runCycle(SchedulingPolicy.BIN_PACKING);

        mvc.perform(get("/api/v1/jobs/" + jobId + "/decisions").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("PLACED"))
                .andExpect(jsonPath("$[0].policy").value("BIN_PACKING"))
                .andExpect(jsonPath("$[0].candidates[0].verdict").value("CHOSEN"))
                .andExpect(jsonPath("$[0].candidates[0].workerName").value("gpu-node"))
                .andExpect(jsonPath("$[0].candidates[1].verdict").value("MISSING_LABELS"));
        mvc.perform(get("/api/v1/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.schedulingOutcome").value("PLACED"));
        mvc.perform(get("/api/v1/jobs/" + jobId + "/decisions").header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/scheduler/decisions").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/scheduler/decisions").header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(jobId));
    }

    @Test
    void schedulerStatus_reportsTheActivePolicy() throws Exception {
        mvc.perform(get("/api/v1/scheduler").header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy").value("FIFO"))
                .andExpect(jsonPath("$.availablePolicies.length()").value(SchedulingPolicy.values().length));
    }

    private void registerWorker(String name, String label) throws Exception {
        var labels = label == null ? "" : "\"" + label + "\"";
        mvc.perform(post("/worker-api/v1/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + IntegrationTest.WORKER_BOOTSTRAP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","version":"t","labels":[%s],
                                 "capacity":{"cpuMillis":4000,"memoryMib":8192,"accelerators":%d,"slots":4}}
                                """.formatted(name, labels, label == null ? 0 : 2)))
                .andExpect(status().isCreated());
    }
}
