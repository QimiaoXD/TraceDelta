package io.tracedelta.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.runner.EvalRunStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceInstrumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", "trace test"))))
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

    private long createMockProvider(String token, String name, String model, String mockConfigJson) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "providerType", "MOCK",
                                "defaultModel", model,
                                "mockConfigJson", mockConfigJson))))
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
        throw new AssertionError("Run did not reach terminal state in time: " + runId);
    }

    @Test
    void recordsOtelSpanHierarchyExposesTraceApisAndCascadesDelete() throws Exception {
        String token = loginAsAdmin();
        String jsonl = """
                {"case_key":"tr-k1","input":{"question":"Use Bearer secret_abc12345 to answer"},"reference_answer":"OK"}
                {"case_key":"tr-k2","input":{"question":"Second question"},"reference_answer":"OK2"}
                """;
        long datasetId = createDataset(token, "trace-e2e-ds", jsonl);
        long providerId = createMockProvider(
                token,
                "trace-e2e-mock",
                "mock-good",
                "{\"profile\":\"mock-good\",\"seed\":7,\"latencyMs\":5}");

        MvcResult createRunRes = mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "trace-e2e-run",
                                "datasetId", datasetId,
                                "datasetVersionNo", 1,
                                "providerId", providerId,
                                "systemPrompt", "Be concise",
                                "concurrency", 2,
                                "maxAttempts", 3))))
                .andExpect(status().isOk())
                .andReturn();
        long runId = objectMapper.readTree(createRunRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        JsonNode runDetail = waitUntilTerminal(token, runId);
        assertThat(runDetail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runDetail.path("traceIncomplete").asBoolean()).isFalse();
        assertThat(runDetail.path("traceDroppedCount").asInt()).isEqualTo(0);

        MvcResult resultsRes = mockMvc.perform(get("/api/runs/" + runId + "/results")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstResult = objectMapper.readTree(resultsRes.getResponse().getContentAsString())
                .path("data").get(0);
        long resultId = firstResult.path("id").asLong();
        String traceId = firstResult.path("traceId").asText();
        assertThat(traceId).matches("^[0-9a-f]{32}$");

        // Fetch trace via result endpoint
        MvcResult traceByResultRes = mockMvc.perform(get("/api/runs/" + runId + "/results/" + resultId + "/trace")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(traceId))
                .andExpect(jsonPath("$.data.caseKey").value("tr-k1"))
                .andReturn();

        JsonNode traceData = objectMapper.readTree(traceByResultRes.getResponse().getContentAsString()).path("data");
        JsonNode spans = traceData.path("spans");
        assertThat(spans.size()).isEqualTo(4);

        List<String> kinds = new ArrayList<>();
        String rootSpanId = null;
        String attemptSpanId = null;
        JsonNode llmSpan = null;
        for (JsonNode s : spans) {
            String kind = s.path("spanKind").asText();
            kinds.add(kind);
            if ("CASE_ROOT".equals(kind)) {
                rootSpanId = s.path("spanId").asText();
                assertThat(s.path("parentSpanId").isNull()).isTrue();
            } else if ("ATTEMPT".equals(kind)) {
                attemptSpanId = s.path("spanId").asText();
                assertThat(s.path("parentSpanId").asText()).isEqualTo(rootSpanId);
            } else if ("LLM_CALL".equals(kind)) {
                llmSpan = s;
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("CASE_ROOT", "ATTEMPT", "PROMPT_RENDER", "LLM_CALL");
        assertThat(llmSpan).isNotNull();
        assertThat(llmSpan.path("parentSpanId").asText()).isEqualTo(attemptSpanId);
        assertThat(llmSpan.path("inputPayload").asText()).doesNotContain("secret_abc12345").contains("Bearer [REDACTED]");
        JsonNode attrs = objectMapper.readTree(llmSpan.path("attributesJson").asText());
        assertThat(attrs.path("gen_ai.system").asText()).isEqualTo("mock");
        assertThat(attrs.path("gen_ai.operation.name").asText()).isEqualTo("chat");
        assertThat(attrs.path("gen_ai.request.model").asText()).isEqualTo("mock-good");
        assertThat(attrs.path("gen_ai.usage.input_tokens").asInt()).isGreaterThan(0);

        // Fetch same trace directly by traceId
        mockMvc.perform(get("/api/traces/" + traceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(traceId))
                .andExpect(jsonPath("$.data.spans.length()").value(4));

        // Writer stats endpoint
        mockMvc.perform(get("/api/traces/writer-stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flushedCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(8)));

        // Delete run cascades to trace_span
        mockMvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/traces/" + traceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordsMultipleAttemptSpansUnderSameRootWhenFlakyCaseRetries() throws Exception {
        String token = loginAsAdmin();
        String jsonl = """
                {"case_key":"flaky-1","input":{"question":"Test retry trace"},"reference_answer":"OK"}
                {"case_key":"flaky-2","input":{"question":"Test retry trace 2"},"reference_answer":"OK"}
                {"case_key":"flaky-3","input":{"question":"Test retry trace 3"},"reference_answer":"OK"}
                {"case_key":"flaky-4","input":{"question":"Test retry trace 4"},"reference_answer":"OK"}
                """;
        long datasetId = createDataset(token, "trace-flaky-ds", jsonl);
        long providerId = createMockProvider(
                token,
                "trace-flaky-mock",
                "mock-flaky",
                "{\"profile\":\"mock-flaky\",\"seed\":42,\"latencyMs\":0,\"errorRate\":0.7}");

        MvcResult createRunRes = mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "trace-flaky-run",
                                "datasetId", datasetId,
                                "datasetVersionNo", 1,
                                "providerId", providerId,
                                "concurrency", 2,
                                "maxAttempts", 5))))
                .andExpect(status().isOk())
                .andReturn();
        long runId = objectMapper.readTree(createRunRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        waitUntilTerminal(token, runId);

        MvcResult resultsRes = mockMvc.perform(get("/api/runs/" + runId + "/results")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode retriedResult = null;
        for (JsonNode item : objectMapper.readTree(resultsRes.getResponse().getContentAsString()).path("data")) {
            if (item.path("attemptCount").asInt() >= 2) {
                retriedResult = item;
                break;
            }
        }
        assertThat(retriedResult).as("At least one flaky case should have retried").isNotNull();

        MvcResult traceRes = mockMvc.perform(get("/api/traces/" + retriedResult.path("traceId").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode spans = objectMapper.readTree(traceRes.getResponse().getContentAsString())
                .path("data").path("spans");

        long attemptSpanCount = 0;
        long errorAttemptCount = 0;
        for (JsonNode s : spans) {
            if ("ATTEMPT".equals(s.path("spanKind").asText())) {
                attemptSpanCount++;
                if ("ERROR".equals(s.path("status").asText())) {
                    errorAttemptCount++;
                }
            }
        }
        assertThat(attemptSpanCount).isEqualTo(retriedResult.path("attemptCount").asInt());
        assertThat(errorAttemptCount).isGreaterThanOrEqualTo(1);
    }
}
