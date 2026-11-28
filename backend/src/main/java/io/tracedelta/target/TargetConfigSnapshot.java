package io.tracedelta.target;

import io.tracedelta.provider.ProviderType;
import io.tracedelta.target.http.HttpTargetEntity;

/**
 * Sanitized immutable target configuration frozen at Run creation time.
 * Deliberately excludes any plaintext or encrypted API keys or sensitive HTTP headers.
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
        String userPromptTemplate,
        Long httpTargetId,
        String httpTargetName,
        String httpUrl,
        String httpMethod,
        String httpHeadersMaskedJson,
        String httpBodyTemplate,
        String httpResponseJsonPath) {

    public TargetConfigSnapshot(
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
        this(
                targetType,
                providerId,
                providerName,
                providerType,
                baseUrl,
                model,
                pricingJson,
                mockConfigJson,
                systemPrompt,
                userPromptTemplate,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static TargetConfigSnapshot forHttp(HttpTargetEntity target) {
        return new TargetConfigSnapshot(
                TargetType.HTTP,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                target.getId(),
                target.getName(),
                target.getUrl(),
                target.getHttpMethod(),
                target.getHeadersMaskedJson(),
                target.getBodyTemplate(),
                target.getResponseJsonPath());
    }
}
