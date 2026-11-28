package io.tracedelta.target.http;

public record HttpTargetSmokeTestView(
        boolean success,
        Integer httpStatus,
        long latencyMs,
        String extractedOutput,
        String rawResponsePreview,
        String errorType,
        String errorMessage) {
}
