package io.tracedelta.target;

import io.tracedelta.provider.llm.LlmResponse;
import io.tracedelta.trace.SpanDraft;
import java.util.List;

public record TargetExecutionResult(LlmResponse response, List<SpanDraft> spans) {

    public TargetExecutionResult {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    public TargetExecutionResult(LlmResponse response) {
        this(response, List.of());
    }
}
