package io.tracedelta.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new ObjectMapper());

    @Test
    void redactsBearerBasicOpenAiKeysAndInlineApiKeyInText() {
        String raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret "
                + "and Basic dXNlcjpwYXNz "
                + "and key sk-proj-1234567890abcdefXYZ "
                + "and api_key=my_secret_val_99";

        String sanitized = sanitizer.sanitizeText(raw, 4096);

        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(sanitized).doesNotContain("dXNlcjpwYXNz");
        assertThat(sanitized).doesNotContain("1234567890abcdefXYZ");
        assertThat(sanitized).doesNotContain("my_secret_val_99");
        assertThat(sanitized).contains("Bearer [REDACTED]");
        assertThat(sanitized).contains("Basic [REDACTED]");
        assertThat(sanitized).contains("sk-[REDACTED]");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    void redactsSensitiveKeysRecursivelyInJsonWhilePreservingSafeFields() {
        String rawJson = """
                {
                  "headers": {
                    "Authorization": "Bearer top-secret-token",
                    "X-Api-Key": "secret-api-key-123",
                    "Cookie": "session=abc123",
                    "Content-Type": "application/json",
                    "X-Tenant": "tracedelta"
                  },
                  "gen_ai.request.model": "gpt-4o-mini",
                  "nested": [
                    {"access_token": "tok_xyz", "note": "使用 sk-live-abcdefgh123456 调用"}
                  ]
                }
                """;

        String sanitized = sanitizer.sanitizeJson(rawJson, 8192);

        assertThat(sanitized).doesNotContain("top-secret-token");
        assertThat(sanitized).doesNotContain("secret-api-key-123");
        assertThat(sanitized).doesNotContain("session=abc123");
        assertThat(sanitized).doesNotContain("tok_xyz");
        assertThat(sanitized).doesNotContain("abcdefgh123456");
        assertThat(sanitized).contains("application/json");
        assertThat(sanitized).contains("tracedelta");
        assertThat(sanitized).contains("gpt-4o-mini");
        assertThat(sanitized).contains("sk-[REDACTED]");
    }

    @Test
    void masksHeadersMapPreservingNonSensitiveHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer my-token");
        headers.put("x-api-key", "abc12345");
        headers.put("Accept", "application/json");

        Map<String, String> masked = sanitizer.maskHeaders(headers);

        assertThat(masked).containsEntry("Authorization", "[REDACTED]");
        assertThat(masked).containsEntry("x-api-key", "[REDACTED]");
        assertThat(masked).containsEntry("Accept", "application/json");
    }

    @Test
    void truncatesOversizedPayloadsCleanly() {
        String huge = "A".repeat(200);
        String truncated = sanitizer.sanitizeText(huge, 50);

        assertThat(truncated.length()).isLessThanOrEqualTo(50);
        assertThat(truncated).endsWith("...[truncated]");
    }

    @Test
    void sanitizesAllTraceSpanFieldsBeforeEnqueue() {
        TraceSpanEntity span = new TraceSpanEntity();
        span.setInputPayload("{\"api_key\":\"secret999\",\"q\":\"hello\"}");
        span.setOutputPayload("Returned Bearer token1234567890");
        span.setAttributesJson("{\"http.request.headers\":{\"Authorization\":\"Bearer abc\"}}");
        span.setErrorMessage("Failed with sk-test-123456789012345");

        TraceSpanEntity sanitized = sanitizer.sanitizeSpan(span, 2048);

        assertThat(sanitized.getInputPayload()).doesNotContain("secret999").contains("hello");
        assertThat(sanitized.getOutputPayload()).doesNotContain("token1234567890").contains("Bearer [REDACTED]");
        assertThat(sanitized.getAttributesJson()).doesNotContain("Bearer abc").contains("[REDACTED]");
        assertThat(sanitized.getErrorMessage()).doesNotContain("123456789012345").contains("sk-[REDACTED]");
    }
}
