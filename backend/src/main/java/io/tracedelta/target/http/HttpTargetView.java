package io.tracedelta.target.http;

import java.time.LocalDateTime;
import java.util.Map;

public record HttpTargetView(
        Long id,
        String name,
        String description,
        String url,
        String httpMethod,
        Map<String, String> maskedHeaders,
        String bodyTemplate,
        String responseJsonPath,
        Long defaultTimeoutMs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
