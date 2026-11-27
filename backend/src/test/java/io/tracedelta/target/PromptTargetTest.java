package io.tracedelta.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.provider.ProviderConfigEntity;
import io.tracedelta.provider.ProviderService;
import io.tracedelta.provider.ProviderType;
import io.tracedelta.provider.llm.LlmClientRouter;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.provider.llm.LlmRequest;
import io.tracedelta.provider.llm.LlmResponse;
import io.tracedelta.provider.llm.MockLlmClient;
import io.tracedelta.provider.llm.OpenAiCompatibleLlmClient;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PromptTargetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mockTargetUsesFrozenSnapshotAndPassesReferenceAnswerAndAttemptNo() {
        ProviderService providerService = mock(ProviderService.class);
        MockLlmClient mockClient = new MockLlmClient(objectMapper);
        OpenAiCompatibleLlmClient openAiClient = mock(OpenAiCompatibleLlmClient.class);
        LlmClientRouter router = new LlmClientRouter(mockClient, openAiClient);
        PromptTemplateRenderer renderer = new PromptTemplateRenderer(objectMapper);
        PromptTarget target = new PromptTarget(router, providerService, renderer);

        TargetConfigSnapshot config = new TargetConfigSnapshot(
                TargetType.PROMPT,
                10L,
                "mock-p",
                ProviderType.MOCK,
                null,
                "mock-good",
                "{\"inputPer1k\":0.01,\"outputPer1k\":0.02}",
                "{\"profile\":\"mock-good\",\"seed\":42,\"latencyMs\":0}",
                "You are helpful",
                "{{question}}");
        CaseSnapshot caseSnapshot = new CaseSnapshot(
                "case-1", "h1", "{\"question\":\"2+2?\"}", null, "4");

        TargetExecutionResult result = target.execute(
                new TargetExecutionContext(config, caseSnapshot, 2, Duration.ofSeconds(5)));

        assertThat(target.rateLimitKey(config)).isEqualTo("provider:10");
        assertThat(result.response().errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(result.response().content()).isEqualTo("4");
        assertThat(result.response().estimatedCost()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void openAiTargetDecryptsApiKeyAtExecutionTimeAndNeverStoresItInSnapshot() throws Exception {
        ProviderService providerService = mock(ProviderService.class);
        ProviderConfigEntity dbEntity = new ProviderConfigEntity();
        dbEntity.setId(20L);
        dbEntity.setApiKeyCiphertext("enc-secret");
        when(providerService.getEntity(20L)).thenReturn(dbEntity);
        when(providerService.decryptApiKey(dbEntity)).thenReturn("sk-live-secret");

        OpenAiCompatibleLlmClient openAiClient = mock(OpenAiCompatibleLlmClient.class);
        when(openAiClient.chat(any(), eq("sk-live-secret"), any()))
                .thenReturn(LlmResponse.ok("ok-answer", 12L, 10, 5, BigDecimal.ZERO));
        LlmClientRouter router = new LlmClientRouter(new MockLlmClient(objectMapper), openAiClient);
        PromptTarget target = new PromptTarget(router, providerService, new PromptTemplateRenderer(objectMapper));

        TargetConfigSnapshot config = new TargetConfigSnapshot(
                TargetType.PROMPT,
                20L,
                "openai-p",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "gpt-4o-mini",
                null,
                null,
                null,
                "{{question}}");
        String snapshotJson = objectMapper.writeValueAsString(config);
        assertThat(snapshotJson).doesNotContain("apiKey").doesNotContain("sk-live-secret").doesNotContain("enc-secret");

        CaseSnapshot caseSnapshot = new CaseSnapshot("c-openai", "h", "{\"question\":\"hi\"}", null, null);
        TargetExecutionResult result = target.execute(
                new TargetExecutionContext(config, caseSnapshot, 1, Duration.ofSeconds(10)));

        assertThat(result.response().content()).isEqualTo("ok-answer");
        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(openAiClient).chat(any(), eq("sk-live-secret"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().model()).isEqualTo("gpt-4o-mini");
        assertThat(reqCaptor.getValue().caseKey()).isEqualTo("c-openai");
    }

    @Test
    void deletedOpenAiProviderReturnsNonRetryableFailureWithoutThrowing() {
        ProviderService providerService = mock(ProviderService.class);
        when(providerService.getEntity(99L)).thenThrow(new BizException(ErrorCode.NOT_FOUND, "模型渠道不存在: 99"));

        LlmClientRouter router = new LlmClientRouter(new MockLlmClient(objectMapper), mock(OpenAiCompatibleLlmClient.class));
        PromptTarget target = new PromptTarget(router, providerService, new PromptTemplateRenderer(objectMapper));

        TargetConfigSnapshot config = new TargetConfigSnapshot(
                TargetType.PROMPT,
                99L,
                "deleted-p",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "gpt-4o-mini",
                null,
                null,
                null,
                "{{question}}");
        CaseSnapshot caseSnapshot = new CaseSnapshot("c-del", "h", "{\"question\":\"hi\"}", null, null);

        TargetExecutionResult result = target.execute(
                new TargetExecutionContext(config, caseSnapshot, 1, Duration.ofSeconds(5)));

        assertThat(result.response().errorType()).isEqualTo(LlmErrorType.MALFORMED_RESPONSE);
        assertThat(result.response().errorMessage()).contains("99");
    }
}
