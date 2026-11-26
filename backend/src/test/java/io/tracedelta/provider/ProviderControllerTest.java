package io.tracedelta.provider;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class ProviderControllerTest {

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
    void createListUpdateDeleteFlow() throws Exception {
        String token = loginAsAdmin();

        String createBody = objectMapper.writeValueAsString(Map.of(
                "name", "ctrl-provider",
                "providerType", "OPENAI_COMPATIBLE",
                "baseUrl", "https://api.example.com/v1",
                "apiKey", "sk-controllersecret5678",
                "defaultModel", "gpt-4o-mini"));

        MvcResult createRes = mockMvc.perform(post("/api/providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMask").value("sk-****5678"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.apiKeyCiphertext").doesNotExist())
                .andExpect(content().string(not(containsString("controllersecret"))))
                .andReturn();

        long id = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/providers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("controllersecret"))));

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "name", "ctrl-provider-updated",
                "providerType", "OPENAI_COMPATIBLE",
                "baseUrl", "https://api.example.com/v1",
                "defaultModel", "gpt-4o"));

        mockMvc.perform(put("/api/providers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("ctrl-provider-updated"))
                .andExpect(jsonPath("$.data.apiKeyMask").value("sk-****5678"));

        mockMvc.perform(delete("/api/providers/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void endpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/providers"))
                .andExpect(status().isUnauthorized());
    }
}
