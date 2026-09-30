package io.github.martiaaguilera.quantarun.controlplane.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OpenApiDocumentTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openApiDocument_describesTheVersionedApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/jobs'].post").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/jobs/{jobId}/cancel'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer").exists());
    }
}
