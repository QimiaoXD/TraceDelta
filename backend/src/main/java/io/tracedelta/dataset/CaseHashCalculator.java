package io.tracedelta.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class CaseHashCalculator {

    private final ObjectMapper objectMapper;

    public CaseHashCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String compute(DatasetCaseInput c) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("input", canonicalize(c.input()));
        payload.put("messages", canonicalize(c.messages()));
        payload.put("reference_answer", c.referenceAnswer());
        payload.put("expected_doc_ids", c.expectedDocIds());
        payload.put("assertions", canonicalize(objectMapper.valueToTree(c.assertions())));

        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jsonBytes);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to compute canonical case_hash", ex);
        }
    }

    private Object canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(canonicalize(child));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }
}
