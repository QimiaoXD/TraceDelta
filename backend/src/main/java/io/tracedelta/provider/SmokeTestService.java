package io.tracedelta.provider;

import io.tracedelta.provider.llm.LlmClient;
import io.tracedelta.provider.llm.LlmClientRouter;
import io.tracedelta.provider.llm.LlmMessage;
import io.tracedelta.provider.llm.LlmRequest;
import io.tracedelta.provider.llm.LlmResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SmokeTestService {

    private final ProviderService providerService;
    private final LlmClientRouter llmClientRouter;

    public SmokeTestService(ProviderService providerService, LlmClientRouter llmClientRouter) {
        this.providerService = providerService;
        this.llmClientRouter = llmClientRouter;
    }

    public LlmResponse run(Long providerId, String prompt, String modelOverride) {
        ProviderConfigEntity entity = providerService.getEntity(providerId);
        String apiKeyPlain = providerService.decryptApiKey(entity);
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : entity.getDefaultModel();
        LlmRequest request = new LlmRequest(
                model,
                List.of(new LlmMessage("user", prompt)),
                "smoke",
                1,
                Map.of(),
                Duration.ofSeconds(30));
        LlmClient client = llmClientRouter.forType(ProviderType.valueOf(entity.getProviderType()));
        return client.chat(entity, apiKeyPlain, request);
    }
}
