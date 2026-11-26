package io.tracedelta.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.provider.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SmokeTestServiceTest {

    @Autowired
    private ProviderService providerService;

    @Autowired
    private SmokeTestService smokeTestService;

    @Test
    void mockProviderSmokeTestReturnsResponse() {
        ProviderView created = providerService.create(new ProviderUpsertRequest(
                "smoke-mock",
                ProviderType.MOCK,
                null,
                null,
                "mock-good",
                null,
                "{\"profile\":\"mock-good\",\"seed\":1}"));

        LlmResponse res = smokeTestService.run(created.id(), "hello", null);
        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.content()).contains("hello");
        assertThat(res.inputTokens()).isGreaterThan(0);
    }

    @Test
    void unknownProviderIsNotFound() {
        assertThatThrownBy(() -> smokeTestService.run(777777L, "hello", null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
