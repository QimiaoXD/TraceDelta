package io.tracedelta.target;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.llm.LlmMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");
    private static final String DEFAULT_USER_TEMPLATE = "{{question}}";

    private final ObjectMapper objectMapper;

    public PromptTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<LlmMessage> renderMessages(
            String systemPrompt, String userPromptTemplate, CaseSnapshot caseSnapshot) {
        List<LlmMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new LlmMessage("system", systemPrompt.trim()));
        }

        if (caseSnapshot.messagesJson() != null && !caseSnapshot.messagesJson().isBlank()) {
            List<LlmMessage> multiTurn = parseMultiTurnMessages(caseSnapshot.messagesJson());
            if (!multiTurn.isEmpty()) {
                messages.addAll(multiTurn);
                return List.copyOf(messages);
            }
        }

        String template = (userPromptTemplate == null || userPromptTemplate.isBlank())
                ? DEFAULT_USER_TEMPLATE
                : userPromptTemplate;
        JsonNode inputNode = parseInputNode(caseSnapshot.inputJson());
        String renderedUser = substitute(template, inputNode);
        messages.add(new LlmMessage("user", renderedUser));
        return List.copyOf(messages);
    }

    private String substitute(String template, JsonNode inputNode) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = resolveValue(inputNode, key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
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

    private JsonNode parseInputNode(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(inputJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<LlmMessage> parseMultiTurnMessages(String messagesJson) {
        try {
            JsonNode root = objectMapper.readTree(messagesJson);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<LlmMessage> parsed = new ArrayList<>();
            for (JsonNode node : root) {
                String role = node.path("role").asText("").trim();
                String content = node.path("content").asText("");
                if (!role.isEmpty()) {
                    parsed.add(new LlmMessage(role, content));
                }
            }
            return parsed;
        } catch (Exception ex) {
            return List.of();
        }
    }
}
