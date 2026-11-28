package io.tracedelta.target.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "tracedelta.security.ssrf.allowed-hosts=127.0.0.1,localhost")
class HttpTargetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HttpTargetMapper httpTargetMapper;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String loginAsAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    void rejectsSsrfMetadataUrlOnCreate() throws Exception {
        String token = loginAsAdmin();
        Map<String, Object> body = Map.of(
                "name", "ssrf-meta-target",
                "url", "http://169.254.169.254/latest/meta-data/",
                "httpMethod", "GET");

        mockMvc.perform(post("/api/http-targets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void crudEncryptsSensitiveHeadersMasksViewsAndRunsSmokeTest() throws Exception {
        String token = loginAsAdmin();
        String endpointUrl = server.url("/api/qa").toString();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer super-secret-http-token");
        headers.put("X-Tenant", "acme-ai");

        Map<String, Object> createPayload = new LinkedHashMap<>();
        createPayload.put("name", "qa-service-api");
        createPayload.put("description", "External QA service");
        createPayload.put("url", endpointUrl);
        createPayload.put("httpMethod", "POST");
        createPayload.put("headers", headers);
        createPayload.put("bodyTemplate", "{\"query\":\"{{question}}\"}");
        createPayload.put("responseJsonPath", "$.data.reply");
        createPayload.put("defaultTimeoutMs", 10000);

        MvcResult createRes = mockMvc.perform(post("/api/http-targets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("qa-service-api"))
                .andExpect(jsonPath("$.data.maskedHeaders.Authorization").value("[REDACTED]"))
                .andExpect(jsonPath("$.data.maskedHeaders['X-Tenant']").value("acme-ai"))
                .andReturn();

        long targetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // Verify at-rest encryption in DB
        HttpTargetEntity stored = httpTargetMapper.selectById(targetId);
        assertThat(stored.getHeadersCiphertext()).isNotBlank().doesNotContain("super-secret-http-token");
        assertThat(stored.getHeadersMaskedJson()).doesNotContain("super-secret-http-token").contains("[REDACTED]");

        // Update description with headers omitted -> existing encrypted headers preserved
        Map<String, Object> updatePayload = new LinkedHashMap<>();
        updatePayload.put("name", "qa-service-api");
        updatePayload.put("description", "Updated desc");
        updatePayload.put("url", endpointUrl);
        updatePayload.put("httpMethod", "POST");
        updatePayload.put("bodyTemplate", "{\"query\":\"{{question}}\"}");
        updatePayload.put("responseJsonPath", "$.data.reply");
        updatePayload.put("defaultTimeoutMs", 15000);

        mockMvc.perform(put("/api/http-targets/" + targetId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Updated desc"))
                .andExpect(jsonPath("$.data.maskedHeaders.Authorization").value("[REDACTED]"));

        // Smoke test
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"reply\":\"Smoke reply OK\"}}"));

        MvcResult smokeRes = mockMvc.perform(post("/api/http-targets/" + targetId + "/test")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputJson\":\"{\\\"question\\\":\\\"Hello \\\\\\\"world\\\\\\\"\\\"}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.httpStatus").value(200))
                .andExpect(jsonPath("$.data.extractedOutput").value("Smoke reply OK"))
                .andReturn();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer super-secret-http-token");
        assertThat(recorded.getHeader("X-Tenant")).isEqualTo("acme-ai");
        JsonNode sentBody = objectMapper.readTree(recorded.getBody().readUtf8());
        assertThat(sentBody.path("query").asText()).isEqualTo("Hello \"world\"");
        assertThat(smokeRes.getResponse().getContentAsString()).doesNotContain("super-secret-http-token");

        // List & Delete
        mockMvc.perform(get("/api/http-targets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isNumber());

        mockMvc.perform(delete("/api/http-targets/" + targetId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/http-targets/" + targetId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
