package io.tracedelta.runner;

import io.tracedelta.target.TargetType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateEvalRunRequest(
        String name,
        @NotNull(message = "datasetId 不能为空") Long datasetId,
        @NotNull(message = "datasetVersionNo 不能为空") Integer datasetVersionNo,
        TargetType targetType,
        Long providerId,
        Long httpTargetId,
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

    public CreateEvalRunRequest(
            String name,
            Long datasetId,
            Integer datasetVersionNo,
            TargetType targetType,
            Long providerId,
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
        this(
                name,
                datasetId,
                datasetVersionNo,
                targetType,
                providerId,
                null,
                model,
                systemPrompt,
                userPromptTemplate,
                concurrency,
                maxAttempts,
                requestTimeoutMs,
                rateLimitPerSec,
                rateLimitBurst,
                maxCostUsd,
                maxTotalTokens);
    }
}
