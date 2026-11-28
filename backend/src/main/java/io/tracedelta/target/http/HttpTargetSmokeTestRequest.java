package io.tracedelta.target.http;

public record HttpTargetSmokeTestRequest(
        String inputJson,
        Long timeoutMs) {
}
