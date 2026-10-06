package io.github.martiaaguilera.quantarun.controlplane.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SimulationApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    SimulationAdmission admission;

    @Test
    void run_comparesPolicies_andIsStoredForTheProjectThatAskedForIt() throws Exception {
        var support = new ApiTestSupport(mvc, json);
        var owner = support.createProject();
        var stranger = support.createProject();

        var created = run(owner.bearer(), """
                        {"scenario":"NOISY_NEIGHBOR","seed":42,"jobCount":300,"policies":["FIFO","FAIR_SHARE"]}
                        """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("NOISY_NEIGHBOR"))
                .andExpect(jsonPath("$.results.policies.length()").value(2))
                .andExpect(jsonPath("$.results.policies[0].policy").value("FIFO"))
                .andExpect(jsonPath("$.results.policies[1].metrics.fairness").isNumber())
                .andExpect(jsonPath("$.results.policies[1].resultHash").isString())
                .andReturn();
        var body = json.readTree(created.getResponse().getContentAsString());
        var id = body.get("id").asString();

        mvc.perform(get("/api/v1/simulations/" + id).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.policies[1].resultHash")
                        .value(body.at("/results/policies/1/resultHash").asString()));
        mvc.perform(get("/api/v1/simulations/" + id).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/simulations/" + id).header(HttpHeaders.AUTHORIZATION, ApiTestSupport.adminBearer()))
                .andExpect(status().isOk());
    }

    /**
     * Simulations are CPU-bound and any project key may start one, so only a bounded number run at once; the rest are
     * refused, not queued (Phase 14 review). The permits are taken directly, so the test does not depend on timing.
     */
    @Test
    void simulationsBeyondTheConcurrencyBound_areRefusedUntilOneFinishes() throws Exception {
        var request = "{\"scenario\":\"STEADY\",\"seed\":1,\"jobCount\":50,\"policies\":[\"FIFO\"]}";
        assertThat(admission.tryEnter()).isTrue();
        assertThat(admission.tryEnter()).isTrue();
        try {
            run(ApiTestSupport.adminBearer(), request)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("SIMULATION_BUSY"));
        } finally {
            admission.leave();
        }
        run(ApiTestSupport.adminBearer(), request).andExpect(status().isCreated());
        admission.leave();
        run(ApiTestSupport.adminBearer(), request).andExpect(status().isCreated());
    }

    /** I15 through the API: the same request twice gives byte-identical deterministic results. */
    @Test
    void theSameRequest_givesTheSameHashes() throws Exception {
        var request = "{\"scenario\":\"WORKER_FAILURE\",\"seed\":7,\"jobCount\":200}";
        var first = json.readTree(run(ApiTestSupport.adminBearer(), request)
                .andReturn()
                .getResponse()
                .getContentAsString());
        var second = json.readTree(run(ApiTestSupport.adminBearer(), request)
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(first.at("/results/policies").size()).isEqualTo(6);
        for (int i = 0; i < 6; i++) {
            assertThat(second.at("/results/policies/" + i + "/resultHash"))
                    .isEqualTo(first.at("/results/policies/" + i + "/resultHash"));
            assertThat(second.at("/results/policies/" + i + "/metrics"))
                    .isEqualTo(first.at("/results/policies/" + i + "/metrics"));
        }
    }

    @Test
    void requestsAreValidatedAndBounded() throws Exception {
        var admin = ApiTestSupport.adminBearer();
        run(admin, "{\"scenario\":\"STEADY\",\"seed\":1,\"jobCount\":0}").andExpect(status().isBadRequest());
        run(admin, "{\"scenario\":\"STEADY\",\"seed\":1,\"jobCount\":20001}").andExpect(status().isBadRequest());
        run(admin, "{\"scenario\":\"NOPE\",\"seed\":1}").andExpect(status().isBadRequest());
        run(admin, "{\"scenario\":\"STEADY\"}").andExpect(status().isBadRequest());
        run(admin, "{\"scenario\":\"STEADY\",\"seed\":1,\"policies\":[]}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/simulations/scenarios").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
        mvc.perform(post("/api/v1/simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"STEADY\",\"seed\":1}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions run(String bearer, String body) throws Exception {
        return mvc.perform(post("/api/v1/simulations")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
