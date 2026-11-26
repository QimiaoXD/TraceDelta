package io.tracedelta.provider.llm;

import io.tracedelta.provider.ProviderType;
import org.springframework.stereotype.Component;

@Component
public class LlmClientRouter {

    private final MockLlmClient mockLlmClient;
    private final OpenAiCompatibleLlmClient openAiCompatibleLlmClient;

    public LlmClientRouter(
            MockLlmClient mockLlmClient, OpenAiCompatibleLlmClient openAiCompatibleLlmClient) {
        this.mockLlmClient = mockLlmClient;
        this.openAiCompatibleLlmClient = openAiCompatibleLlmClient;
    }

    public LlmClient forType(ProviderType type) {
        return switch (type) {
            case MOCK -> mockLlmClient;
            case OPENAI_COMPATIBLE -> openAiCompatibleLlmClient;
        };
    }
}
