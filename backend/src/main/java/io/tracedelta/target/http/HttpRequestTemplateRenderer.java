package io.tracedelta.target.http;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Renders HTTP request body templates containing {@code {{var}}} placeholders.
 * When a placeholder sits inside a JSON string literal, the substituted value is JSON-escaped
 * so quotes, backslashes, and newlines in test inputs never corrupt request JSON.
 */
@Component
public class HttpRequestTemplateRenderer {

    private final ObjectMapper objectMapper;

    public HttpRequestTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String renderBody(String bodyTemplate, String inputJson) {
        if (bodyTemplate == null || bodyTemplate.isBlank()) {
            return null;
        }
        JsonNode root = parseInput(inputJson);
        String trimmed = bodyTemplate.trim();
        boolean jsonLike = trimmed.startsWith("{") || trimmed.startsWith("[");

        StringBuilder out = new StringBuilder(bodyTemplate.length() + 64);
        boolean inJsonString = false;
        boolean escaped = false;
        int i = 0;
        int len = bodyTemplate.length();

        while (i < len) {
            char c = bodyTemplate.charAt(i);
            if (c == '{' && i + 1 < len && bodyTemplate.charAt(i + 1) == '{') {
                int closeIdx = bodyTemplate.indexOf("}}", i + 2);
                if (closeIdx > i + 2) {
                    String rawKey = bodyTemplate.substring(i + 2, closeIdx).trim();
                    if (isValidKey(rawKey)) {
                        String resolved = resolveValue(root, rawKey);
                        if (jsonLike && inJsonString) {
                            out.append(JsonStringEncoder.getInstance().quoteAsString(resolved));
                        } else {
                            out.append(resolved);
                        }
                        i = closeIdx + 2;
                        continue;
                    }
                }
            }

            if (jsonLike) {
                if (inJsonString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inJsonString = false;
                    }
                } else if (c == '"') {
                    inJsonString = true;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private boolean isValidKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private JsonNode parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(inputJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveValue(JsonNode root, String key) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode direct = root.get(key);
        if (direct != null && !direct.isNull()) {
            return direct.isValueNode() ? direct.asText() : direct.toString();
        }
        if (key.contains(".")) {
            JsonNode curr = root;
            for (String segment : key.split("\\.")) {
                if (curr == null || !curr.isObject()) {
                    return "";
                }
                curr = curr.get(segment);
            }
            if (curr != null && !curr.isNull()) {
                return curr.isValueNode() ? curr.asText() : curr.toString();
            }
        }
        return "";
    }
}
