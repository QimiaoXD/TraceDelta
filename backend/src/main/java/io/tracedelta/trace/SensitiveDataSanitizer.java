package io.tracedelta.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitiveDataSanitizer {

    public static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATED_SUFFIX = "...[truncated]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization",
            "proxy-authorization",
            "api-key",
            "x-api-key",
            "api_key",
            "apikey",
            "token",
            "access_token",
            "refresh_token",
            "secret",
            "client_secret",
            "password",
            "cookie",
            "set-cookie");

    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}");
    private static final Pattern BASIC_PATTERN =
            Pattern.compile("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{4,}");
    private static final Pattern SK_KEY_PATTERN =
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}");
    private static final Pattern INLINE_API_KEY_PATTERN =
            Pattern.compile("(?i)\\b(api[-_]?key|access_token|secret)\\s*([:=])\\s*([^\\s,\"'&}]+)");

    private final ObjectMapper objectMapper;

    public SensitiveDataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("-api-key")
                || normalized.endsWith("_api_key")
                || normalized.endsWith("-token")
                || normalized.endsWith("_token")
                || normalized.endsWith("-secret")
                || normalized.endsWith("_secret");
    }

    public Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (isSensitiveKey(key)) {
                masked.put(key, REDACTED);
            } else {
                masked.put(key, sanitizeInlineSecrets(entry.getValue()));
            }
        }
        return masked;
    }

    public String sanitizeText(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String redacted = sanitizeInlineSecrets(raw);
        return truncate(redacted, maxChars);
    }

    public String sanitizeJson(String rawJson, int maxChars) {
        if (rawJson == null) {
            return null;
        }
        String trimmed = rawJson.trim();
        if (trimmed.isEmpty()) {
            return rawJson;
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                JsonNode cleaned = sanitizeNode(root);
                String serialized = objectMapper.writeValueAsString(cleaned);
                return truncate(serialized, maxChars);
            } catch (Exception ignored) {
                // Fall back to text-level redaction when input is not valid JSON
            }
        }
        return sanitizeText(rawJson, maxChars);
    }

    public TraceSpanEntity sanitizeSpan(TraceSpanEntity span, int maxPayloadChars) {
        if (span == null) {
            return null;
        }
        span.setInputPayload(sanitizeJson(span.getInputPayload(), maxPayloadChars));
        span.setOutputPayload(sanitizeJson(span.getOutputPayload(), maxPayloadChars));
        span.setAttributesJson(sanitizeJson(span.getAttributesJson(), maxPayloadChars));
        span.setErrorMessage(sanitizeText(span.getErrorMessage(), 980));
        return span;
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (isSensitiveKey(key)) {
                    obj.put(key, REDACTED);
                } else {
                    obj.set(key, sanitizeNode(entry.getValue()));
                }
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(sanitizeNode(child));
            }
            return arr;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeInlineSecrets(node.asText()));
        }
        return node;
    }

    private String sanitizeInlineSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = BEARER_PATTERN.matcher(text).replaceAll("Bearer " + REDACTED);
        result = BASIC_PATTERN.matcher(result).replaceAll("Basic " + REDACTED);
        result = SK_KEY_PATTERN.matcher(result).replaceAll("sk-" + REDACTED);
        result = INLINE_API_KEY_PATTERN.matcher(result).replaceAll("$1$2" + REDACTED);
        return result;
    }

    private String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= TRUNCATED_SUFFIX.length()) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }
}
