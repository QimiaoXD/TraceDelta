package io.tracedelta.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.provider.ProviderConfigEntity;
import io.tracedelta.provider.ProviderService;
import io.tracedelta.provider.ProviderType;
import io.tracedelta.provider.llm.LlmClient;
import io.tracedelta.provider.llm.LlmClientRouter;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.provider.llm.LlmMessage;
import io.tracedelta.provider.llm.LlmRequest;
import io.tracedelta.provider.llm.LlmResponse;
import io.tracedelta.trace.SpanDraft;
import io.tracedelta.trace.SpanKind;
import io.tracedelta.trace.SpanStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PromptTarget implements EvalTarget {

    private final LlmClientRouter llmClientRouter;
    private final ProviderService providerService;
    private final PromptTemplateRenderer renderer;
    private final ObjectMapper objectMapper;

    public PromptTarget(
            LlmClientRouter llmClientRouter,
            ProviderService providerService,
            PromptTemplateRenderer renderer) {
        this(llmClientRouter, providerService, renderer, new ObjectMapper());
    }

    @Autowired
    public PromptTarget(
            LlmClientRouter llmClientRouter,
            ProviderService providerService,
            PromptTemplateRenderer renderer,
            ObjectMapper objectMapper) {
        this.llmClientRouter = llmClientRouter;
        this.providerService = providerService;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public TargetType type() {
        return TargetType.PROMPT;
    }

    @Override
    public String rateLimitKey(TargetConfigSnapshot config) {
        return "provider:" + config.providerId();
    }

    @Override
    public TargetExecutionResult execute(TargetExecutionContext context) {
        TargetConfigSnapshot cfg = context.targetConfig();
        CaseSnapshot cs = context.caseSnapshot();
        List<SpanDraft> spans = new ArrayList<>(2);

        long renderStartMs = System.currentTimeMillis();
        List<LlmMessage> messages = renderer.renderMessages(cfg.systemPrompt(), cfg.userPromptTemplate(), cs);
        long renderEndMs = Math.max(renderStartMs, System.currentTimeMillis());

        String renderedMessagesJson = toJsonQuietly(messages);
        Map<String, Object> renderAttrs = new LinkedHashMap<>();
        renderAttrs.put("tracedelta.template.has_system_prompt", cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank());
        renderAttrs.put("tracedelta.messages.count", messages.size());
        spans.add(new SpanDraft(
                "prompt.render",
                SpanKind.PROMPT_RENDER,
                SpanStatus.OK,
                renderStartMs,
                renderEndMs,
                null,
                null,
                null,
                null,
                cs.inputJson(),
                renderedMessagesJson,
                renderAttrs,
                null,
                null));

        long llmStartMs = System.currentTimeMillis();
        String apiKeyPlain = null;
        if (cfg.providerType() == ProviderType.OPENAI_COMPATIBLE) {
            try {
                ProviderConfigEntity liveProvider = providerService.getEntity(cfg.providerId());
                apiKeyPlain = providerService.decryptApiKey(liveProvider);
            } catch (BizException ex) {
                long llmEndMs = Math.max(llmStartMs, System.currentTimeMillis());
                LlmResponse failure = LlmResponse.failure(
                        0L, LlmErrorType.MALFORMED_RESPONSE, "渠道不可用: " + ex.getMessage());
                spans.add(buildLlmSpan(cfg, context.attemptNo(), renderedMessagesJson, failure, llmStartMs, llmEndMs));
                return new TargetExecutionResult(failure, spans);
            }
        }

        ProviderConfigEntity snapshotProvider = new ProviderConfigEntity();
        snapshotProvider.setId(cfg.providerId());
        snapshotProvider.setName(cfg.providerName());
        snapshotProvider.setProviderType(cfg.providerType().name());
        snapshotProvider.setBaseUrl(cfg.baseUrl());
        snapshotProvider.setDefaultModel(cfg.model());
        snapshotProvider.setPricingJson(cfg.pricingJson());
        snapshotProvider.setMockConfigJson(cfg.mockConfigJson());

        Map<String, String> metadata = new LinkedHashMap<>();
        if (cs.referenceAnswer() != null) {
            metadata.put("referenceAnswer", cs.referenceAnswer());
        }

        LlmRequest request = new LlmRequest(
                cfg.model(),
                messages,
                cs.caseKey(),
                context.attemptNo(),
                metadata,
                context.timeout());

        LlmClient client = llmClientRouter.forType(cfg.providerType());
        LlmResponse response = client.chat(snapshotProvider, apiKeyPlain, request);
        long llmEndMs = Math.max(llmStartMs, System.currentTimeMillis());
        spans.add(buildLlmSpan(cfg, context.attemptNo(), renderedMessagesJson, response, llmStartMs, llmEndMs));
        return new TargetExecutionResult(response, spans);
    }

    private SpanDraft buildLlmSpan(
            TargetConfigSnapshot cfg,
            int attemptNo,
            String renderedMessagesJson,
            LlmResponse response,
            long startMs,
            long endMs) {
        boolean ok = response.errorType() == LlmErrorType.NONE;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("gen_ai.system", cfg.providerType().name().toLowerCase(Locale.ROOT));
        attrs.put("gen_ai.operation.name", "chat");
        attrs.put("gen_ai.request.model", cfg.model());
        attrs.put("gen_ai.usage.input_tokens", response.inputTokens());
        attrs.put("gen_ai.usage.output_tokens", response.outputTokens());
        attrs.put("tracedelta.provider_name", cfg.providerName());
        attrs.put("tracedelta.attempt_no", attemptNo);
        if (response.ttftMs() != null) {
            attrs.put("tracedelta.ttft_ms", response.ttftMs());
        }
        return new SpanDraft(
                "gen_ai.chat",
                SpanKind.LLM_CALL,
                ok ? SpanStatus.OK : SpanStatus.ERROR,
                startMs,
                endMs,
                response.ttftMs(),
                response.inputTokens(),
                response.outputTokens(),
                response.estimatedCost(),
                renderedMessagesJson,
                ok ? response.content() : null,
                attrs,
                ok ? null : response.errorType().name(),
                ok ? null : response.errorMessage());
    }

    private String toJsonQuietly(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return String.valueOf(obj);
        }
    }
}
