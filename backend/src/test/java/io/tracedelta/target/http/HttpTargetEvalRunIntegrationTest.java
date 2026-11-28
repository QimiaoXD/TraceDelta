package io.tracedelta.target.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.runner.EvalRunStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
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
class HttpTargetEvalRunIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private long createDataset(String token, String name, String jsonl) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", "http eval test"))))
                .andExpect(status().isOk())
                .andReturn();
        long datasetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/datasets/" + datasetId + "/versions/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("format", "JSONL", "content", jsonl, "note", "v1"))))
                .andExpect(status().isOk());
        return datasetId;
    }

    private long createHttpTarget(String token, String name, String url, String jsonPath) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("url", url);
        payload.put("httpMethod", "POST");
        payload.put("headers", Map.of("Authorization", "Bearer http-run-secret-999"));
        payload.put("bodyTemplate", "{\"question\":\"{{question}}\"}");
        payload.put("responseJsonPath", jsonPath);
        payload.put("defaultTimeoutMs", 10000);

        MvcResult res = mockMvc.perform(post("/api/http-targets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private JsonNode waitUntilTerminal(String token, long runId) throws Exception {
        for (int i = 0; i < 120; i++) {
            MvcResult res = mockMvc.perform(get("/api/runs/" + runId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
            if (EvalRunStatus.valueOf(data.path("status").asText()).isTerminal()) {
                return data;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Run did not finish in time: " + runId);
    }

    @Test
    void executesHttpTargetRunWithRetriesTraceCaptureAndSecretRedaction() throws Exception {
        String token = loginAsAdmin();
        final java.util.concurrent.atomic.AtomicInteger retryCaseCalls = new java.util.concurrent.atomic.AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                if (body.contains("retry-me") && retryCaseCalls.incrementAndGet() == 1) {
                    return new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limited\"}");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"data\":{\"answer\":\"HTTP Answer for " + (body.contains("retry-me") ? "retry" : "normal") + "\"}}");
            }
        });

        String jsonl = """
                {"case_key":"http-ok","input":{"question":"normal query"}}
                {"case_key":"http-retry","input":{"question":"retry-me query"}}
                """;
        long datasetId = createDataset(token, "http-e2e-ds", jsonl);
        long httpTargetId = createHttpTarget(token, "http-e2e-target", server.url("/invoke").toString(), "$.data.answer");

        Map<String, Object> createRun = new LinkedHashMap<>();
        createRun.put("name", "http-target-run");
        createRun.put("datasetId", datasetId);
        createRun.put("datasetVersionNo", 1);
        createRun.put("targetType", "HTTP");
        createRun.put("httpTargetId", httpTargetId);
        createRun.put("concurrency", 2);
        createRun.put("maxAttempts", 3);

        MvcResult runRes = mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRun)))
                .andExpect(status().isOk())
                .andReturn();
        long runId = objectMapper.readTree(runRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        JsonNode finished = waitUntilTerminal(token, runId);
        assertThat(finished.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.path("targetType").asText()).isEqualTo("HTTP");
        assertThat(finished.path("httpTargetId").asLong()).isEqualTo(httpTargetId);
        assertThat(finished.path("providerId").isNull()).isTrue();
        assertThat(finished.path("targetConfigSnapshotJson").asText())
                .doesNotContain("http-run-secret-999")
                .contains("[REDACTED]");

        MvcResult resultsRes = mockMvc.perform(get("/api/runs/" + runId + "/results")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode results = objectMapper.readTree(resultsRes.getResponse().getContentAsString()).path("data");
        assertThat(results.size()).isEqualTo(2);

        JsonNode okCase = results.get(0);
        JsonNode retryCase = results.get(1);
        assertThat(okCase.path("outputText").asText()).isEqualTo("HTTP Answer for normal");
        assertThat(retryCase.path("outputText").asText()).isEqualTo("HTTP Answer for retry");
        assertThat(retryCase.path("attemptCount").asInt()).isEqualTo(2);

        // Verify outer HTTP_CALL span and redacted headers
        MvcResult traceRes = mockMvc.perform(get("/api/runs/" + runId + "/results/" + okCase.path("id").asLong() + "/trace")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String traceJson = traceRes.getResponse().getContentAsString();
        assertThat(traceJson).doesNotContain("http-run-secret-999");

        JsonNode spans = objectMapper.readTree(traceJson).path("data").path("spans");
        JsonNode httpSpan = null;
        for (JsonNode s : spans) {
            if ("HTTP_CALL".equals(s.path("spanKind").asText())) {
                httpSpan = s;
                break;
            }
        }
        assertThat(httpSpan).isNotNull();
        JsonNode attrs = objectMapper.readTree(httpSpan.path("attributesJson").asText());
        assertThat(attrs.path("http.request.method").asText()).isEqualTo("POST");
        assertThat(attrs.path("http.response.status_code").asInt()).isEqualTo(200);
    }

    @Test
    void blocks302RedirectAndUnmatchedJsonPathWithoutRetrying() throws Exception {
        String token = loginAsAdmin();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                if (body.contains("redirect-case")) {
                    return new MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", "http://169.254.169.254/latest/meta-data/");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"other\":\"no answer field\"}");
            }
        });

        String jsonl = """
                {"case_key":"c-redirect","input":{"question":"redirect-case"}}
                {"case_key":"c-badpath","input":{"question":"badpath-case"}}
                """;
        long datasetId = createDataset(token, "http-guard-ds", jsonl);
        long httpTargetId = createHttpTarget(token, "http-guard-target", server.url("/guard").toString(), "$.data.answer");

        Map<String, Object> createRun = new LinkedHashMap<>();
        createRun.put("name", "http-guard-run");
        createRun.put("datasetId", datasetId);
        createRun.put("datasetVersionNo", 1);
        createRun.put("targetType", "HTTP");
        createRun.put("httpTargetId", httpTargetId);
        createRun.put("concurrency", 2);
        createRun.put("maxAttempts", 3);

        MvcResult runRes = mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRun)))
                .andExpect(status().isOk())
                .andReturn();
        long runId = objectMapper.readTree(runRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        JsonNode finished = waitUntilTerminal(token, runId);
        assertThat(finished.path("status").asText()).isEqualTo("FAILED");

        MvcResult resultsRes = mockMvc.perform(get("/api/runs/" + runId + "/results")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode results = objectMapper.readTree(resultsRes.getResponse().getContentAsString()).path("data");

        JsonNode redirectRes = results.get(0);
        JsonNode badPathRes = results.get(1);
        assertThat(redirectRes.path("errorType").asText()).isEqualTo("SSRF_BLOCKED");
        assertThat(redirectRes.path("attemptCount").asInt()).isEqualTo(1);
        assertThat(badPathRes.path("errorType").asText()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(badPathRes.path("attemptCount").asInt()).isEqualTo(1);
    }
}
