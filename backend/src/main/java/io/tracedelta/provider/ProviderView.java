package io.tracedelta.provider;

public record ProviderView(
        Long id,
        String name,
        ProviderType providerType,
        String baseUrl,
        String apiKeyMask,
        String defaultModel,
        String pricingJson,
        String mockConfigJson) {

    public static ProviderView fromEntity(ProviderConfigEntity entity) {
        return new ProviderView(
                entity.getId(),
                entity.getName(),
                ProviderType.valueOf(entity.getProviderType()),
                entity.getBaseUrl(),
                entity.getApiKeyMask(),
                entity.getDefaultModel(),
                entity.getPricingJson(),
                entity.getMockConfigJson());
    }
}
