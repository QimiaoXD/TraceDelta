package io.tracedelta.provider.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        String caseKey,
        int attemptNo,
        Map<String, String> metadata,
        Duration timeout) {

    public LlmRequest {
        messages = (messages == null) ? List.of() : List.copyOf(messages);
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
        timeout = (timeout == null) ? Duration.ofSeconds(30) : timeout;
    }
}
