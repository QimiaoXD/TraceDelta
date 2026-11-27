package io.tracedelta.target;

import io.tracedelta.provider.llm.LlmResponse;

public record TargetExecutionResult(LlmResponse response) {
}
