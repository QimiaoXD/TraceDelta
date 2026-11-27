package io.tracedelta.target;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PromptTarget implements EvalTarget {

    private final LlmClientRouter llmClientRouter;
    private final ProviderService providerService;
    private final PromptTemplateRenderer renderer;

    public PromptTarget(
            LlmClientRouter llmClientRouter,
            ProviderService providerService,
            PromptTemplateRenderer renderer) {
        this.llmClientRouter = llmClientRouter;
        this.providerService = providerService;
        this.renderer = renderer;
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

        String apiKeyPlain = null;
        if (cfg.providerType() == ProviderType.OPENAI_COMPATIBLE) {
            try {
                ProviderConfigEntity liveProvider = providerService.getEntity(cfg.providerId());
                apiKeyPlain = providerService.decryptApiKey(liveProvider);
            } catch (BizException ex) {
                return new TargetExecutionResult(
                        LlmResponse.failure(0L, LlmErrorType.MALFORMED_RESPONSE, "渠道不可用: " + ex.getMessage()));
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

        List<LlmMessage> messages = renderer.renderMessages(cfg.systemPrompt(), cfg.userPromptTemplate(), cs);
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
        return new TargetExecutionResult(response);
    }
}
