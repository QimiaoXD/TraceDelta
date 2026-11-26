package io.tracedelta.provider.llm;

import java.math.BigDecimal;

public record LlmResponse(
        String content,
        long latencyMs,
        Long ttftMs,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCost,
        LlmErrorType errorType,
        String errorMessage) {

    public static LlmResponse ok(
            String content,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCost) {
        return new LlmResponse(
                content,
                latencyMs,
                null,
                inputTokens,
                outputTokens,
                estimatedCost == null ? BigDecimal.ZERO : estimatedCost,
                LlmErrorType.NONE,
                null);
    }

    public static LlmResponse failure(long latencyMs, LlmErrorType errorType, String errorMessage) {
        return new LlmResponse(
                null,
                latencyMs,
                null,
                0,
                0,
                BigDecimal.ZERO,
                errorType,
                errorMessage);
    }
}
