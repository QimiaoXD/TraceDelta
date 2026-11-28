package io.tracedelta.target;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.llm.LlmMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptTemplateRendererTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer(new ObjectMapper());

    @Test
    void rendersNamedVariablesFromInputJson() {
        CaseSnapshot snapshot = new CaseSnapshot(
                "c1", "h1", "{\"question\":\"什么是虚拟线程？\",\"context\":\"Java 21\"}", null, "ref");

        List<LlmMessage> messages = renderer.renderMessages(
                "你是架构师", "上下文：{{ context }}\n问题：{{question}}", snapshot);

        assertThat(messages).containsExactly(
                new LlmMessage("system", "你是架构师"),
                new LlmMessage("user", "上下文：Java 21\n问题：什么是虚拟线程？"));
    }

    @Test
    void defaultsBlankUserTemplateToQuestionAndHandlesMissingVariables() {
        CaseSnapshot snapshot = new CaseSnapshot(
                "c2", "h2", "{\"question\":\"Hello\"}", null, null);

        List<LlmMessage> defaultMsgs = renderer.renderMessages(null, "  ", snapshot);
        assertThat(defaultMsgs).containsExactly(new LlmMessage("user", "Hello"));

        List<LlmMessage> missingVarMsgs = renderer.renderMessages(null, "Q={{question}} X={{missing}}", snapshot);
        assertThat(missingVarMsgs).containsExactly(new LlmMessage("user", "Q=Hello X="));
    }

    @Test
    void preservesMultiTurnMessagesAndPrependsSystemPrompt() {
        String multiTurn = "[{\"role\":\"user\",\"content\":\"第一轮\"},{\"role\":\"assistant\",\"content\":\"收到\"},{\"role\":\"user\",\"content\":\"第二轮\"}]";
        CaseSnapshot snapshot = new CaseSnapshot("c3", "h3", "{}", multiTurn, null);

        List<LlmMessage> messages = renderer.renderMessages("系统指令", "{{question}}", snapshot);

        assertThat(messages).containsExactly(
                new LlmMessage("system", "系统指令"),
                new LlmMessage("user", "第一轮"),
                new LlmMessage("assistant", "收到"),
                new LlmMessage("user", "第二轮"));
    }
}
