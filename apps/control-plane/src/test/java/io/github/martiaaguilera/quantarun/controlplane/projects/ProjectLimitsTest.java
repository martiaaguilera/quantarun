package io.github.martiaaguilera.quantarun.controlplane.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.jobs.JobSubmission;
import io.github.martiaaguilera.quantarun.controlplane.jobs.ResourceRequest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.WorkloadType;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Project weights and quotas: who may set them, and admission control against {@code maxQueuedJobs}. */
@IntegrationTest
class ProjectLimitsTest {

    private static final String JOB = """
            {"workloadType":"delay","payload":{"durationMs":10},"resources":{"cpuMillis":100,"memoryMib":64}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobLifecycle lifecycle;

    ApiTestSupport.TestProject project;

    @BeforeEach
    void setUp() throws Exception {
        project = new ApiTestSupport(mvc, json).createProject();
    }

    @Test
    void onlyOperatorsSetLimits_andTheyReplaceThePreviousOnes() throws Exception {
        limits(project.bearer(), "{\"weight\":5}").andExpect(status().isForbidden());

        limits(ApiTestSupport.adminBearer(), "{\"weight\":5,\"maxQueuedJobs\":10,\"maxRunningJobs\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(5))
                .andExpect(jsonPath("$.maxQueuedJobs").value(10))
                .andExpect(jsonPath("$.maxRunningJobs").value(2))
                .andExpect(jsonPath("$.maxAccelerators").doesNotExist());
        // PUT semantics: what is omitted is unlimited again.
        limits(ApiTestSupport.adminBearer(), "{\"weight\":2}")
                .andExpect(jsonPath("$.maxQueuedJobs").doesNotExist())
                .andExpect(jsonPath("$.maxRunningJobs").doesNotExist());

        limits(ApiTestSupport.adminBearer(), "{\"maxRunningJobs\":2}").andExpect(status().isBadRequest());
        limits(ApiTestSupport.adminBearer(), "{\"weight\":1,\"maxRunningJobs\":0}")
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/projects/" + UUID.randomUUID() + "/limits")
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submissionsBeyondTheQueueQuota_areRejectedWithAReason() throws Exception {
        limits(ApiTestSupport.adminBearer(), "{\"weight\":1,\"maxQueuedJobs\":2}")
                .andExpect(status().isOk());

        var first = submit("key-1").andExpect(status().isCreated());
        submit(null).andExpect(status().isCreated());
        submit(null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("its quota is 2")));
        // A retry of a submission that already got in adds no work, so it is replayed even at the quota.
        submit("key-1").andExpect(status().isOk());

        var firstId = json.readTree(first.andReturn().getResponse().getContentAsString())
                .get("id")
                .asString();
        mvc.perform(post("/api/v1/jobs/" + firstId + "/cancel").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                .andExpect(status().isOk());
        submit(null).andExpect(status().isCreated());
    }

    @Test
    void concurrentSubmissions_neverOvershootTheQueueQuota() throws Exception {
        limits(ApiTestSupport.adminBearer(), "{\"weight\":1,\"maxQueuedJobs\":10}")
                .andExpect(status().isOk());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 40; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        lifecycle.submit(project.id(), submission(), null);
                        return true;
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo("QUOTA_EXCEEDED");
                        return false;
                    }
                }));
            }
            start.countDown();
            var admitted = 0;
            for (var future : futures) {
                admitted += future.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(10);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE project_id = :p")
                        .param("p", project.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(10);
    }

    @Test
    void fairnessView_isForOperators() throws Exception {
        mvc.perform(get("/api/v1/scheduler/fairness").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/scheduler/fairness").header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemVirtualTime").isNumber())
                .andExpect(jsonPath("$.projects").isArray());
    }

    private ResultActions limits(String bearer, String body) throws Exception {
        return mvc.perform(put("/api/v1/projects/" + project.id() + "/limits")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions submit(String idempotencyKey) throws Exception {
        var request = post("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, project.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JOB);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private JobSubmission submission() {
        return new JobSubmission(
                WorkloadType.DELAY,
                json.createObjectNode().put("durationMs", 10),
                4,
                new ResourceRequest(100, 64, 0),
                List.of(),
                3,
                60,
                null,
                null);
    }
}
