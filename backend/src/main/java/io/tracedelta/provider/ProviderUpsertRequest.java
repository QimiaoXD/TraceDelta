package io.tracedelta.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProviderUpsertRequest(
        @NotBlank String name,
        @NotNull ProviderType providerType,
        String baseUrl,
        String apiKey,
        @NotBlank String defaultModel,
        String pricingJson,
        String mockConfigJson) {
}
