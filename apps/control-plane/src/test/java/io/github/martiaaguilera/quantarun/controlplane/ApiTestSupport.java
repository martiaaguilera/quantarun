package io.github.martiaaguilera.quantarun.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Creates isolated projects through the real admin API, so every test starts from its own tenant. */
public final class ApiTestSupport {

    private static final AtomicInteger PROJECT_SEQUENCE = new AtomicInteger();

    public record TestProject(UUID id, String name, String apiKey) {
        public String bearer() {
            return "Bearer " + apiKey;
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;

    public ApiTestSupport(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public static String adminBearer() {
        return "Bearer " + IntegrationTest.ADMIN_TOKEN;
    }

    public TestProject createProject() throws Exception {
        var name = "test-" + PROJECT_SEQUENCE.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        var project = postAsAdmin("/api/v1/projects", "{\"name\":\"" + name + "\",\"weight\":1}");
        var projectId = UUID.fromString(project.get("id").asString());
        var issued = postAsAdmin("/api/v1/projects/" + projectId + "/api-keys", "{\"label\":\"tests\"}");
        return new TestProject(projectId, name, issued.get("secret").asString());
    }

    private JsonNode postAsAdmin(String path, String body) throws Exception {
        var response = mvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }
}
