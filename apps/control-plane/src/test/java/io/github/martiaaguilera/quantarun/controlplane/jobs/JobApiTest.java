package io.github.martiaaguilera.quantarun.controlplane.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport.TestProject;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class JobApiTest {

    private static final String DELAY_JOB = """
            {"workloadType":"delay","payload":{"durationMs":250},"resources":{"cpuMillis":500,"memoryMib":128}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    TestProject project;

    @BeforeEach
    void createProject() throws Exception {
        project = new ApiTestSupport(mvc, json).createProject();
    }

    @Test
    void submit_createsQueuedJobWithDefaultsAndSubmittedEvent() throws Exception {
        var jobId = submit(project, DELAY_JOB, null)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/jobs/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.priority").value(JobSubmission.DEFAULT_PRIORITY))
                .andExpect(jsonPath("$.maxAttempts").value(JobSubmission.DEFAULT_MAX_ATTEMPTS))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.resources.accelerators").value(0))
                .andReturn();
        var id = json.readTree(jobId.getResponse().getContentAsString())
                .get("id")
                .asString();

        mvc.perform(get("/api/v1/jobs/" + id + "/events").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].details.workloadType").value("delay"));
    }

    @Nested
    class Authentication {

        @Test
        void missingCredentials_are401ProblemWithChallenge() throws Exception {
            mvc.perform(post("/api/v1/jobs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DELAY_JOB))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/problem+json")))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void wellFormedButUnknownKey_is401() throws Exception {
            var forged = "qr_0123abcd_" + "A".repeat(43);
            mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void keyWithCorrectPrefixButWrongSecret_is401() throws Exception {
            var tampered = project.apiKey().substring(0, project.apiKey().length() - 1)
                    + (project.apiKey().endsWith("A") ? "B" : "A");
            mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void revokedKey_stopsWorkingImmediately() throws Exception {
            var keys = jdbc.sql("SELECT id FROM api_keys WHERE project_id = :p")
                    .param("p", project.id())
                    .query(UUID.class)
                    .list();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                    "/api/v1/projects/" + project.id() + "/api-keys/" + keys.getFirst())
                            .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                    .andExpect(status().isUnauthorized());
        }

        /** The secret is shown once, at issue; listing a project's keys returns only the lookup prefix. */
        @Test
        void listingKeys_neverReturnsTheSecret() throws Exception {
            var body = mvc.perform(get("/api/v1/projects/" + project.id() + "/api-keys")
                            .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].prefix").isString())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).doesNotContain(project.apiKey().substring(12)).doesNotContain("secret");
        }

        @Test
        void storedKeyMaterial_isHashNotPlaintext() {
            var stored = jdbc.sql("SELECT secret_hash FROM api_keys WHERE project_id = :p")
                    .param("p", project.id())
                    .query(byte[].class)
                    .single();
            assertThat(stored).hasSize(32);
            assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain(project.apiKey().substring(12));
        }

        @Test
        void admin_cannotSubmitWithoutAProjectKey() throws Exception {
            mvc.perform(post("/api/v1/jobs")
                            .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DELAY_JOB))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        void projectKey_cannotAdministerProjects() throws Exception {
            mvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class Validation {

        @Test
        void missingResources_listsTheViolatingField() throws Exception {
            submit(project, "{\"workloadType\":\"delay\",\"payload\":{}}", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.violations[0].field").value("resources"));
        }

        @Test
        void outOfRangeValues_areRejected() throws Exception {
            submit(project, """
                            {"workloadType":"delay","payload":{},"priority":12,"maxAttempts":0,
                             "resources":{"cpuMillis":0,"memoryMib":128}}
                            """, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations", hasSize(3)));
        }

        @Test
        void unknownWorkloadType_isRejectedWithItsOwnCode() throws Exception {
            submit(project, DELAY_JOB.replace("\"delay\"", "\"rm -rf /\""), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_WORKLOAD_TYPE"));
        }

        @Test
        void payloadMustBeAnObject() throws Exception {
            submit(project, DELAY_JOB.replace("{\"durationMs\":250}", "[1,2,3]"), null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        void payloadOverTheLimit_isRejected() throws Exception {
            var bigPayload = "{\"blob\":\"" + "x".repeat(JobController.MAX_PAYLOAD_BYTES) + "\"}";
            submit(project, DELAY_JOB.replace("{\"durationMs\":250}", bigPayload), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        }

        @Test
        void bodyOverTheRequestLimit_is413BeforeParsing() throws Exception {
            var huge = "{\"blob\":\"" + "x".repeat(70 * 1024) + "\"}";
            submit(project, DELAY_JOB.replace("{\"durationMs\":250}", huge), null)
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        }

        @Test
        void deadlineInThePast_isRejected() throws Exception {
            var past = Instant.now().minus(1, ChronoUnit.HOURS);
            submit(project, DELAY_JOB.replace("}}", "},\"deadline\":\"" + past + "\"}"), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DEADLINE_IN_PAST"));
        }

        @Test
        void malformedJson_isAProblemWithACode() throws Exception {
            submit(project, "{not json", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void invalidIdempotencyKey_isRejected() throws Exception {
            submit(project, DELAY_JOB, "has space")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Nested
    class Idempotency {

        @Test
        void sameKeySameRequest_replaysTheOriginalJob() throws Exception {
            var first = jobId(submit(project, DELAY_JOB, "order-42").andExpect(status().isCreated()));
            var second = jobId(submit(project, DELAY_JOB, "order-42")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true")));

            assertThat(second).isEqualTo(first);
            assertThat(countJobs(project.id())).isEqualTo(1);
        }

        @Test
        void keyOrderInsideThePayload_doesNotChangeTheFingerprint() throws Exception {
            var a = """
                    {"workloadType":"delay","payload":{"x":1,"y":{"b":2,"a":1}},"resources":{"cpuMillis":500,"memoryMib":128}}
                    """;
            var b = """
                    {"resources":{"memoryMib":128,"cpuMillis":500},"payload":{"y":{"a":1,"b":2},"x":1},"workloadType":"delay"}
                    """;
            submit(project, a, "reordered").andExpect(status().isCreated());
            submit(project, b, "reordered").andExpect(status().isOk());
        }

        @Test
        void explicitDefaultAndOmittedField_areTheSameRequest() throws Exception {
            submit(project, DELAY_JOB, "defaults").andExpect(status().isCreated());
            submit(
                            project,
                            DELAY_JOB.replace("}}", "},\"priority\":" + JobSubmission.DEFAULT_PRIORITY + "}"),
                            "defaults")
                    .andExpect(status().isOk());
        }

        @Test
        void sameKeyDifferentRequest_isConflict() throws Exception {
            submit(project, DELAY_JOB, "order-43").andExpect(status().isCreated());
            submit(project, DELAY_JOB.replace("250", "999"), "order-43")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }

        @Test
        void keysAreScopedPerProject() throws Exception {
            var other = new ApiTestSupport(mvc, json).createProject();
            var mine = jobId(submit(project, DELAY_JOB, "shared-key").andExpect(status().isCreated()));
            var theirs = jobId(submit(other, DELAY_JOB, "shared-key").andExpect(status().isCreated()));
            assertThat(theirs).isNotEqualTo(mine);
        }
    }

    @Nested
    class Isolation {

        @Test
        void anotherProjectsJob_looksMissing() throws Exception {
            var other = new ApiTestSupport(mvc, json).createProject();
            var jobId = jobId(submit(project, DELAY_JOB, null));

            mvc.perform(get("/api/v1/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, other.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
            mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header(HttpHeaders.AUTHORIZATION, other.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, other.bearer()))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        void admin_seesAnyProjectsJob() throws Exception {
            var jobId = jobId(submit(project, DELAY_JOB, null));
            mvc.perform(get("/api/v1/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Listing {

        @Test
        void keysetPagination_walksNewestToOldestWithoutGapsOrDuplicates() throws Exception {
            for (int i = 0; i < 5; i++) {
                submit(project, DELAY_JOB, null).andExpect(status().isCreated());
            }
            var firstPage = json.readTree(
                    mvc.perform(get("/api/v1/jobs?limit=3").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                            .andExpect(jsonPath("$.items", hasSize(3)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString());
            var cursor = firstPage.get("nextBefore").asString();

            mvc.perform(get("/api/v1/jobs?limit=3&before=" + cursor)
                            .header(HttpHeaders.AUTHORIZATION, project.bearer()))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.nextBefore").doesNotExist());
        }

        @Test
        void limitAboveTheCap_isRejected() throws Exception {
            mvc.perform(get("/api/v1/jobs?limit=5000").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Cancellation {

        @Test
        void queuedJob_isCancelledAndCancelIsIdempotent() throws Exception {
            var jobId = jobId(submit(project, DELAY_JOB, null));

            cancel(jobId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("CANCELLED"));
            cancel(jobId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("ALREADY_CANCELLED"));

            mvc.perform(get("/api/v1/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, project.bearer()))
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.finishedAt").exists());
        }

        @Test
        void jobWithActiveAttempt_isFlaggedForCooperativeCancellation() throws Exception {
            var jobId = jobId(submit(project, DELAY_JOB, null));
            forceStatus(jobId, "RUNNING");

            cancel(jobId)
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.outcome").value("CANCEL_REQUESTED"));
            cancel(jobId).andExpect(status().isAccepted());

            var cancelEvents = jdbc.sql(
                            "SELECT count(*) FROM job_events WHERE job_id = :id AND type = 'CANCEL_REQUESTED'")
                    .param("id", jobId)
                    .query(Long.class)
                    .single();
            assertThat(cancelEvents).isEqualTo(1);
        }

        @Test
        void finishedJob_cannotBeCancelled() throws Exception {
            var jobId = jobId(submit(project, DELAY_JOB, null));
            jdbc.sql("UPDATE jobs SET status = 'SUCCEEDED', finished_at = now() WHERE id = :id")
                    .param("id", jobId)
                    .update();

            cancel(jobId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("JOB_NOT_CANCELLABLE"));
        }

        private ResultActions cancel(UUID jobId) throws Exception {
            return mvc.perform(
                    post("/api/v1/jobs/" + jobId + "/cancel").header(HttpHeaders.AUTHORIZATION, project.bearer()));
        }
    }

    private ResultActions submit(TestProject target, String body, String idempotencyKey) throws Exception {
        var request = post("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, target.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private UUID jobId(ResultActions result) throws Exception {
        var body = json.readTree(result.andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asString());
    }

    private long countJobs(UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE project_id = :p")
                .param("p", projectId)
                .query(Long.class)
                .single();
    }

    /** Stands in for the scheduler/worker, which arrive in later phases, to reach states the API cannot. */
    private void forceStatus(UUID jobId, String status) {
        jdbc.sql("UPDATE jobs SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", jobId)
                .update();
    }
}
