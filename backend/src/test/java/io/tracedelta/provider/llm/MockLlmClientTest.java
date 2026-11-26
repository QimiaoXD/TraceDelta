package io.tracedelta.provider.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.ProviderConfigEntity;
import io.tracedelta.provider.ProviderType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockLlmClient client = new MockLlmClient(objectMapper);

    private ProviderConfigEntity mockProvider(String mockConfigJson, String pricingJson) {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setId(1L);
        entity.setName("mock");
        entity.setProviderType(ProviderType.MOCK.name());
        entity.setDefaultModel("mock-model");
        entity.setMockConfigJson(mockConfigJson);
        entity.setPricingJson(pricingJson);
        return entity;
    }

    private LlmRequest req(String caseKey, int attemptNo, String prompt, Map<String, String> meta) {
        return new LlmRequest(
                "mock-model",
                List.of(new LlmMessage("user", prompt)),
                caseKey,
                attemptNo,
                meta,
                Duration.ofSeconds(5));
    }

    @Test
    void goodProfileEchoesReferenceAnswerWhenPresent() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-good\",\"seed\":7}", null);
        LlmResponse res = client.chat(provider, null, req("c1", 1, "Q", Map.of("referenceAnswer", "Expected Answer")));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.content()).isEqualTo("Expected Answer");
        assertThat(res.ttftMs()).isNull();
    }

    @Test
    void goodProfileFallsBackToPromptEcho() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-good\",\"seed\":7}", null);
        LlmResponse res = client.chat(provider, null, req("c1", 1, "Hello TraceDelta", Map.of()));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.content()).isEqualTo("[mock] Hello TraceDelta");
    }

    @Test
    void sloppyProfileIsDeterministicPerCaseKey() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-sloppy\",\"seed\":42}", null);
        String first = client.chat(provider, null, req("case-det", 1, "Q", Map.of("referenceAnswer", "{\"answer\":\"ok\"}"))).content();
        for (int i = 0; i < 20; i++) {
            LlmResponse next = client.chat(provider, null, req("case-det", 1 + (i % 3), "Q", Map.of("referenceAnswer", "{\"answer\":\"ok\"}")));
            assertThat(next.content()).isEqualTo(first);
        }

        int wrongCount = 0;
        for (int i = 0; i < 200; i++) {
            LlmResponse res = client.chat(provider, null, req("sloppy-" + i, 1, "Q", Map.of("referenceAnswer", "{\"ok\":true}")));
            assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
            if (!"{\"ok\":true}".equals(res.content())) {
                wrongCount++;
                assertThatThrownBy(() -> objectMapper.readTree(res.content())).isInstanceOf(Exception.class);
            }
        }
        assertThat(wrongCount).isBetween(50, 110);
    }

    @Test
    void slowProfileHonorsLatency() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-slow\",\"seed\":1,\"latencyMs\":50}", null);
        LlmResponse res = client.chat(provider, null, req("c1", 1, "hi", Map.of()));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.latencyMs()).isGreaterThanOrEqualTo(50L);
    }

    @Test
    void flakyIsDeterministicForSameSeedCaseAndAttempt() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-flaky\",\"seed\":99,\"failRate\":0.5}", null);
        LlmResponse first = client.chat(provider, null, req("flaky-case-1", 1, "hi", Map.of()));
        for (int i = 0; i < 10; i++) {
            LlmResponse next = client.chat(provider, null, req("flaky-case-1", 1, "hi", Map.of()));
            assertThat(next.errorType()).isEqualTo(first.errorType());
            assertThat(next.content()).isEqualTo(first.content());
        }
    }

    @Test
    void flakyAlwaysSucceedsByAttempt3() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-flaky\",\"seed\":99,\"failRate\":0.8}", null);
        for (int i = 0; i < 200; i++) {
            LlmResponse res = client.chat(provider, null, req("k-" + i, 3, "hi", Map.of()));
            assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        }
    }

    @Test
    void flakyFailuresRecoverAcrossAttempts() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-flaky\",\"seed\":123,\"failRate\":0.6}", null);
        String failedKey = null;
        for (int i = 0; i < 200; i++) {
            String key = "recover-" + i;
            if (client.chat(provider, null, req(key, 1, "hi", Map.of())).errorType() != LlmErrorType.NONE) {
                failedKey = key;
                break;
            }
        }
        assertThat(failedKey).isNotNull();
        LlmResponse attempt3 = client.chat(provider, null, req(failedKey, 3, "hi", Map.of()));
        assertThat(attempt3.errorType()).isEqualTo(LlmErrorType.NONE);
    }

    @Test
    void flakyProducesBothErrorKinds() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-flaky\",\"seed\":7,\"failRate\":0.7}", null);
        boolean sawRateLimited = false;
        boolean sawTimeout = false;
        for (int i = 0; i < 200; i++) {
            LlmErrorType err = client.chat(provider, null, req("err-" + i, 1, "hi", Map.of())).errorType();
            if (err == LlmErrorType.RATE_LIMITED) sawRateLimited = true;
            if (err == LlmErrorType.TIMEOUT) sawTimeout = true;
        }
        assertThat(sawRateLimited).isTrue();
        assertThat(sawTimeout).isTrue();
    }

    @Test
    void costIsComputedFromPricing() {
        ProviderConfigEntity provider = mockProvider(
                "{\"profile\":\"mock-good\",\"seed\":1}",
                "{\"inputPer1k\":0.01,\"outputPer1k\":0.02}");
        // input: 4000 chars -> 1000 tokens -> 0.01; output: 2000 chars -> 500 tokens -> 0.01 => total 0.02
        String in = "a".repeat(4000);
        String out = "b".repeat(2000);
        LlmResponse res = client.chat(provider, null, req("c1", 1, in, Map.of("referenceAnswer", out)));
        assertThat(res.estimatedCost()).isEqualByComparingTo(new BigDecimal("0.02"));
    }

    @Test
    void tokensAreEstimatedFromCharacters() {
        ProviderConfigEntity provider = mockProvider("{\"profile\":\"mock-good\",\"seed\":1}", null);
        // 5 chars -> ceil(5/4)=2, 9 chars -> ceil(9/4)=3
        LlmResponse res = client.chat(provider, null, req("c1", 1, "12345", Map.of("referenceAnswer", "123456789")));
        assertThat(res.inputTokens()).isEqualTo(2);
        assertThat(res.outputTokens()).isEqualTo(3);
    }

    @Test
    void mockConfigWithoutProfileAndCustomDefaultModelFallsBackToGood() {
        ProviderConfigEntity provider = mockProvider("{\"seed\":5}", null);
        provider.setDefaultModel("custom-model-name");
        LlmResponse res = client.chat(provider, null, req("c1", 1, "hello", Map.of()));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.content()).isEqualTo("[mock] hello");
    }

    @Test
    void corruptedMockConfigReturnsFailureInsteadOfThrowing() {
        ProviderConfigEntity provider = mockProvider("{corrupted-json", null);
        LlmResponse res = client.chat(provider, null, req("c1", 1, "hello", Map.of()));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.MALFORMED_RESPONSE);
    }
}
