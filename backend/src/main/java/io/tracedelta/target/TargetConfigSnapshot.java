package io.tracedelta.target;

import io.tracedelta.provider.ProviderType;

/**
 * Sanitized immutable target configuration frozen at Run creation time.
 * Deliberately excludes any plaintext or encrypted API keys.
 */
public record TargetConfigSnapshot(
        TargetType targetType,
        Long providerId,
        String providerName,
        ProviderType providerType,
        String baseUrl,
        String model,
        String pricingJson,
        String mockConfigJson,
        String systemPrompt,
        String userPromptTemplate) {
}
