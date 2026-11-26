package io.tracedelta.provider.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.ProviderConfigEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public class MockLlmClient implements LlmClient {

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

    private final ObjectMapper objectMapper;

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse chat(ProviderConfigEntity provider, String apiKeyPlain, LlmRequest request) {
        long startNanos = System.nanoTime();
        MockSettings settings = parseSettings(provider.getMockConfigJson(), provider.getDefaultModel());
        String caseKey = request.caseKey() == null ? "default" : request.caseKey();

        if (settings.profile() == MockProfile.MOCK_SLOW && settings.latencyMs() > 0) {
            sleepMillis(settings.latencyMs());
        } else if (settings.profile() != MockProfile.MOCK_SLOW && settings.latencyMs() > 0) {
            sleepMillis(settings.latencyMs());
        }

        if (settings.profile() == MockProfile.MOCK_FLAKY && request.attemptNo() < 3) {
            long h = stableHash(settings.seed() + "|" + caseKey + "|" + request.attemptNo());
            long threshold = Math.round(settings.failRate() * 1000.0);
            if ((h % 1000L) < threshold) {
                LlmErrorType err = ((h & 1L) == 1L) ? LlmErrorType.RATE_LIMITED : LlmErrorType.TIMEOUT;
                long elapsed = elapsedMs(startNanos);
                return LlmResponse.failure(elapsed, err, "Simulated mock failure: " + err.name());
            }
        }

        String baseText = resolveBaseAnswer(request);
        String outputText = baseText;
        if (settings.profile() == MockProfile.MOCK_SLOPPY) {
            long h = stableHash(settings.seed() + "|" + caseKey);
            if ((h % 100L) < 40L) {
                outputText = "{\"answer\": \"";
            }
        }

        int inputTokens = estimateInputTokens(request);
        int outputTokens = estimateTokens(outputText);
        BigDecimal cost = computeCost(provider.getPricingJson(), inputTokens, outputTokens);
        return LlmResponse.ok(outputText, elapsedMs(startNanos), inputTokens, outputTokens, cost);
    }

    private String resolveBaseAnswer(LlmRequest request) {
        String ref = request.metadata().get("referenceAnswer");
        if (ref != null && !ref.isEmpty()) {
            return ref;
        }
        String lastUser = "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            LlmMessage msg = request.messages().get(i);
            if ("user".equalsIgnoreCase(msg.role())) {
                lastUser = msg.content() == null ? "" : msg.content();
                break;
            }
        }
        return "[mock] " + lastUser;
    }

    private MockSettings parseSettings(String mockConfigJson, String defaultModel) {
        if (mockConfigJson == null || mockConfigJson.isBlank()) {
            MockProfile fallback = tryProfile(defaultModel);
            long defaultLatency = fallback == MockProfile.MOCK_SLOW ? 2000L : 0L;
            return new MockSettings(fallback, 0L, defaultLatency, 0.5);
        }
        try {
            JsonNode root = objectMapper.readTree(mockConfigJson);
            String profileRaw = root.hasNonNull("profile") ? root.get("profile").asText() : defaultModel;
            MockProfile profile = MockProfile.fromName(profileRaw);
            long seed = root.hasNonNull("seed") ? root.get("seed").asLong() : 0L;
            long defaultLatency = profile == MockProfile.MOCK_SLOW ? 2000L : 0L;
            long latencyMs = root.hasNonNull("latencyMs") ? root.get("latencyMs").asLong() : defaultLatency;
            double failRate = root.hasNonNull("failRate") ? root.get("failRate").asDouble() : 0.5;
            return new MockSettings(profile, seed, Math.max(0L, latencyMs), Math.max(0.0, Math.min(1.0, failRate)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid mock_config_json: " + ex.getMessage(), ex);
        }
    }

    private MockProfile tryProfile(String raw) {
        try {
            return MockProfile.fromName(raw);
        } catch (IllegalArgumentException ex) {
            return MockProfile.MOCK_GOOD;
        }
    }

    static long stableHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }

    private int estimateInputTokens(LlmRequest request) {
        int chars = 0;
        for (LlmMessage message : request.messages()) {
            if (message.content() != null) {
                chars += message.content().length();
            }
        }
        return chars == 0 ? 0 : (chars + 3) / 4;
    }

    private BigDecimal computeCost(String pricingJson, int inputTokens, int outputTokens) {
        if (pricingJson == null || pricingJson.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            JsonNode node = objectMapper.readTree(pricingJson);
            BigDecimal inRate = node.hasNonNull("inputPer1k") ? node.get("inputPer1k").decimalValue() : BigDecimal.ZERO;
            BigDecimal outRate = node.hasNonNull("outputPer1k") ? node.get("outputPer1k").decimalValue() : BigDecimal.ZERO;
            BigDecimal inCost = BigDecimal.valueOf(inputTokens).multiply(inRate).divide(ONE_THOUSAND, 8, RoundingMode.HALF_UP);
            BigDecimal outCost = BigDecimal.valueOf(outputTokens).multiply(outRate).divide(ONE_THOUSAND, 8, RoundingMode.HALF_UP);
            return inCost.add(outCost).stripTrailingZeros();
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record MockSettings(MockProfile profile, long seed, long latencyMs, double failRate) {}
}
