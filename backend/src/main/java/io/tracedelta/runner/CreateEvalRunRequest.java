package io.tracedelta.runner;

import io.tracedelta.target.TargetType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateEvalRunRequest(
        String name,
        @NotNull(message = "datasetId 不能为空") Long datasetId,
        @NotNull(message = "datasetVersionNo 不能为空") Integer datasetVersionNo,
        TargetType targetType,
        @NotNull(message = "providerId 不能为空") Long providerId,
        String model,
        String systemPrompt,
        String userPromptTemplate,
        Integer concurrency,
        Integer maxAttempts,
        Long requestTimeoutMs,
        Double rateLimitPerSec,
        Integer rateLimitBurst,
        BigDecimal maxCostUsd,
        Long maxTotalTokens) {
}
