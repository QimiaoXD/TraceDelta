package io.tracedelta.dataset;

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
class DatasetControllerTest {

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

    @Test
    void importJsonlThenListCases() throws Exception {
        String token = loginAsAdmin();

        MvcResult createRes = mockMvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"api-ds\",\"description\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long datasetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String jsonl = """
                {"case_key":"k1","input":{"question":"Q1"},"reference_answer":"A1"}
                {"case_key":"k2","input":{"question":"Q2"},"reference_answer":"A2"}
                """;
        String importBody = objectMapper.writeValueAsString(
                Map.of("format", "JSONL", "content", jsonl, "note", "first import"));

        mockMvc.perform(post("/api/datasets/" + datasetId + "/versions/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.caseCount").value(2));

        mockMvc.perform(get("/api/datasets/" + datasetId + "/versions/1/cases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].caseKey").value("k1"));
    }

    @Test
    void importDuplicateKeyReturns400WithCode() throws Exception {
        String token = loginAsAdmin();

        MvcResult createRes = mockMvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"api-ds-dup\",\"description\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long datasetId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String jsonl = """
                {"case_key":"same","input":{"question":"Q1"}}
                {"case_key":"same","input":{"question":"Q2"}}
                """;
        String importBody = objectMapper.writeValueAsString(
                Map.of("format", "JSONL", "content", jsonl, "note", "dup"));

        mockMvc.perform(post("/api/datasets/" + datasetId + "/versions/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DUPLICATE_CASE_KEY"));
    }

    @Test
    void endpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/datasets"))
                .andExpect(status().isUnauthorized());
    }
}
