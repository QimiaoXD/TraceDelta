package io.tracedelta.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ProviderServiceTest {

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createEncryptsKeyAndStoresMask() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "deepseek",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com/v1",
                "sk-abcdef123456",
                "deepseek-chat",
                null,
                null));

        assertThat(created.apiKeyMask()).isEqualTo("sk-****3456");

        ProviderConfigEntity entity = providerService.getEntity(created.id());
        assertThat(entity.getApiKeyCiphertext()).isNotEqualTo("sk-abcdef123456");
        assertThat(entity.getApiKeyCiphertext()).doesNotContain("abcdef");
        assertThat(providerService.decryptApiKey(entity)).isEqualTo("sk-abcdef123456");
    }

    @Test
    void viewNeverExposesKeyMaterial() throws Exception {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "openai-safe",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                "sk-secretvalue9876",
                "gpt-4o-mini",
                null,
                null));
        ProviderConfigEntity entity = providerService.getEntity(created.id());

        String json = objectMapper.writeValueAsString(created);
        assertThat(json).doesNotContain("sk-secretvalue9876");
        assertThat(json).doesNotContain(entity.getApiKeyCiphertext());
        assertThat(json).contains("sk-****9876");
    }

    @Test
    void updateWithoutApiKeyKeepsOriginalCiphertext() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "keep-key",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "sk-original1234",
                "model-a",
                null,
                null));

        ProviderView updatedNull = providerService.update(created.id(), new ProviderUpsertRequest(
                "keep-key-renamed",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                null,
                "model-b",
                null,
                null));
        assertThat(updatedNull.apiKeyMask()).isEqualTo("sk-****1234");
        assertThat(providerService.decryptApiKey(providerService.getEntity(created.id())))
                .isEqualTo("sk-original1234");

        ProviderView updatedBlank = providerService.update(created.id(), new ProviderUpsertRequest(
                "keep-key-renamed-2",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "   ",
                "model-c",
                null,
                null));
        assertThat(updatedBlank.apiKeyMask()).isEqualTo("sk-****1234");
        assertThat(providerService.decryptApiKey(providerService.getEntity(created.id())))
                .isEqualTo("sk-original1234");
    }

    @Test
    void updateWithNewApiKeyReplacesCiphertext() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "rotate-key",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "sk-oldkey0000",
                "model-a",
                null,
                null));

        ProviderView updated = providerService.update(created.id(), new ProviderUpsertRequest(
                "rotate-key",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "sk-newkey9999",
                "model-a",
                null,
                null));

        assertThat(updated.apiKeyMask()).isEqualTo("sk-****9999");
        assertThat(providerService.decryptApiKey(providerService.getEntity(created.id())))
                .isEqualTo("sk-newkey9999");
    }

    @Test
    void openAiProviderRequiresBaseUrlAndKey() {
        assertThatThrownBy(() -> providerService.create(new ProviderUpsertRequest(
                "bad-openai", ProviderType.OPENAI_COMPATIBLE, "", "sk-12345678", "m", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> providerService.create(new ProviderUpsertRequest(
                "bad-openai-2", ProviderType.OPENAI_COMPATIBLE, "https://x", null, "m", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void mockProviderAllowsMissingKeyAndBaseUrl() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "mock-good-1",
                ProviderType.MOCK,
                null,
                null,
                "mock-good",
                null,
                "{\"profile\":\"mock-good\",\"seed\":1}"));

        assertThat(created.id()).isNotNull();
        assertThat(created.apiKeyMask()).isNull();
        assertThat(providerService.decryptApiKey(providerService.getEntity(created.id()))).isNull();
    }

    @Test
    void deleteUnknownIsNotFound() {
        assertThatThrownBy(() -> providerService.delete(888888L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shortKeyMaskIsStars() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "short-key",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                "1234567",
                "m",
                null,
                null));

        assertThat(created.apiKeyMask()).isEqualTo("****");
    }

    @Test
    void rejectsInvalidMockConfigOrPricingJsonOrBaseUrl() {
        assertThatThrownBy(() -> providerService.create(new ProviderUpsertRequest(
                "bad-mock-json", ProviderType.MOCK, null, null, "mock-good", null, "{bad")))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> providerService.create(new ProviderUpsertRequest(
                "bad-pricing", ProviderType.MOCK, null, null, "mock-good", "{bad", null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> providerService.create(new ProviderUpsertRequest(
                "bad-url", ProviderType.OPENAI_COMPATIBLE, "ftp://bad", "sk-12345678", "m", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
