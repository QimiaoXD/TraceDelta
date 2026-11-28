package io.tracedelta.target.http;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record HttpTargetUpsertRequest(
        @NotBlank(message = "名称不能为空") String name,
        String description,
        @NotBlank(message = "URL 不能为空") String url,
        String httpMethod,
        Map<String, String> headers,
        String bodyTemplate,
        String responseJsonPath,
        Long defaultTimeoutMs) {
}
