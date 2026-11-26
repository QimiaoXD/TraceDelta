package io.tracedelta.provider.llm;

import io.tracedelta.provider.ProviderConfigEntity;

public interface LlmClient {

    LlmResponse chat(ProviderConfigEntity provider, String apiKeyPlain, LlmRequest request);
}
