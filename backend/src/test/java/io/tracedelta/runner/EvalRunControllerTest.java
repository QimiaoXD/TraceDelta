package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class EvalRunControllerTest {

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

    private long createDatasetWithCases(String token, String name) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", "run api test"))))
                .andExpect(status().isOk())
                .andReturn();
        long datasetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String jsonl = """
                {"case_key":"api-k1","input":{"question":"Q1"},"reference_answer":"A1"}
                {"case_key":"api-k2","input":{"question":"Q2"},"reference_answer":"A2"}
                """;
        mockMvc.perform(post("/api/datasets/" + datasetId + "/versions/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("format", "JSONL", "content", jsonl, "note", "v1"))))
                .andExpect(status().isOk());
        return datasetId;
    }

    private long createMockProvider(String token, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "providerType", "MOCK",
                                "defaultModel", "mock-good",
                                "mockConfigJson", "{\"profile\":\"mock-good\",\"seed\":1,\"latencyMs\":0}"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullRunLifecyclePollResultsAttemptsAndCancelCompletedReturns409() throws Exception {
        String token = loginAsAdmin();
        long datasetId = createDatasetWithCases(token, "run-api-ds");
        long providerId = createMockProvider(token, "run-api-mock");

        MvcResult createRunRes = mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "api-lifecycle-run",
                                "datasetId", datasetId,
                                "datasetVersionNo", 1,
                                "providerId", providerId,
                                "concurrency", 2,
                                "maxAttempts", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.totalCases").value(2))
                .andReturn();

        long runId = objectMapper.readTree(createRunRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // Poll until terminal
        String finalStatus = "";
        for (int i = 0; i < 100; i++) {
            MvcResult detailRes = mockMvc.perform(get("/api/runs/" + runId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = objectMapper.readTree(detailRes.getResponse().getContentAsString()).path("data");
            finalStatus = data.path("status").asText();
            if (EvalRunStatus.valueOf(finalStatus).isTerminal()) {
                assertThat(data.path("targetConfigSnapshotJson").asText())
                        .doesNotContain("apiKey")
                        .doesNotContain("apiKeyCiphertext");
                break;
            }
            Thread.sleep(30);
        }
        assertThat(finalStatus).isEqualTo(EvalRunStatus.COMPLETED.name());

        // List runs includes our run
        mockMvc.perform(get("/api/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isNumber());

        // List results
        MvcResult resultsRes = mockMvc.perform(get("/api/runs/" + runId + "/results")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("EXEC_SUCCEEDED"))
                .andReturn();
        long firstResultId = objectMapper.readTree(resultsRes.getResponse().getContentAsString())
                .path("data").get(0).path("id").asLong();

        // List attempts for first result
        mockMvc.perform(get("/api/runs/" + runId + "/results/" + firstResultId + "/attempts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attemptNo").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));

        // Cancelling a COMPLETED run must return 409 INVALID_STATE
        mockMvc.perform(post("/api/runs/" + runId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void validationErrorsAndUnknownRunReturnExpectedStatusCodes() throws Exception {
        String token = loginAsAdmin();
        long datasetId = createDatasetWithCases(token, "run-api-val-ds");
        long providerId = createMockProvider(token, "run-api-val-mock");

        mockMvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "datasetId", datasetId,
                                "datasetVersionNo", 1,
                                "providerId", providerId,
                                "concurrency", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/runs/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
