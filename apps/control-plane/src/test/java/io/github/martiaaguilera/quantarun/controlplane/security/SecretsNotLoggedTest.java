package io.github.martiaaguilera.quantarun.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Secrets never reach the logs: not an issued API key, not the admin token, not a rejected credential. The log
 * pipeline (structured JSON, shipped elsewhere) is wider than the database, so a key that is hashed at rest but
 * printed on use would still leak.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SecretsNotLoggedTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void issuingAndUsingCredentials_logsNoSecret(CapturedOutput output) throws Exception {
        var project = new ApiTestSupport(mvc, json).createProject();
        mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, project.bearer()))
                .andExpect(status().isOk());
        var wrong = "qr_00000000_" + "x".repeat(43);
        mvc.perform(get("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrong))
                .andExpect(status().isUnauthorized());

        assertThat(output.getAll())
                .doesNotContain(project.apiKey())
                .doesNotContain(project.apiKey().substring(12))
                .doesNotContain(ApiTestSupport.adminBearer().substring("Bearer ".length()))
                .doesNotContain(wrong);
    }
}
