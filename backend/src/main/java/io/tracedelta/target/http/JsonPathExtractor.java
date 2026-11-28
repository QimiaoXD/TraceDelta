package io.tracedelta.target.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Lightweight, zero-extra-dependency JSONPath extractor powered by Jackson.
 * Supports root ($), dot fields ($.data.answer), array indices ($.choices[0].message.content),
 * and bracket-quoted keys ($['x-result'][1].text).
 */
@Component
public class JsonPathExtractor {

    private final ObjectMapper objectMapper;

    public JsonPathExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<String> extract(String responseBody, String jsonPath) {
        if (responseBody == null || responseBody.isBlank() || jsonPath == null || jsonPath.isBlank()) {
            return Optional.empty();
        }
        String path = jsonPath.trim();
        if (!path.startsWith("$")) {
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Optional.empty();
        }
        if ("$".equals(path)) {
            return Optional.of(root.isValueNode() ? root.asText() : root.toString());
        }

        JsonNode curr = root;
        int i = 1;
        int len = path.length();
        while (i < len) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
                int start = i;
                while (i < len && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                if (i == start) {
                    return Optional.empty();
                }
                String field = path.substring(start, i);
                if (curr == null || !curr.isObject()) {
                    return Optional.empty();
                }
                curr = curr.get(field);
            } else if (c == '[') {
                int closeIdx = path.indexOf(']', i + 1);
                if (closeIdx < 0) {
                    return Optional.empty();
                }
                String token = path.substring(i + 1, closeIdx).trim();
                if ((token.startsWith("'") && token.endsWith("'"))
                        || (token.startsWith("\"") && token.endsWith("\""))) {
                    if (token.length() < 2 || curr == null || !curr.isObject()) {
                        return Optional.empty();
                    }
                    String field = token.substring(1, token.length() - 1);
                    curr = curr.get(field);
                } else {
                    int index;
                    try {
                        index = Integer.parseInt(token);
                    } catch (NumberFormatException ex) {
                        return Optional.empty();
                    }
                    if (index < 0 || curr == null || !curr.isArray() || index >= curr.size()) {
                        return Optional.empty();
                    }
                    curr = curr.get(index);
                }
                i = closeIdx + 1;
            } else {
                return Optional.empty();
            }
            if (curr == null || curr.isMissingNode() || curr.isNull()) {
                return Optional.empty();
            }
        }
        return Optional.of(curr.isValueNode() ? curr.asText() : curr.toString());
    }
}
