package io.tracedelta.provider.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.ProviderConfigEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse chat(ProviderConfigEntity provider, String apiKeyPlain, LlmRequest request) {
        long startNanos = System.nanoTime();
        Duration timeout = request.timeout() == null ? Duration.ofSeconds(30) : request.timeout();
        String endpoint = normalizeBaseUrl(provider.getBaseUrl()) + "/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", toWireMessages(request.messages()));

        try (HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(timeout).build()) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
            factory.setReadTimeout(timeout);
            RestClient restClient = RestClient.builder().requestFactory(factory).build();

            String rawBody = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKeyPlain == null ? "" : apiKeyPlain))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            long elapsed = elapsedMs(startNanos);
            return parseSuccess(rawBody, provider.getPricingJson(), request, elapsed);
        } catch (RestClientResponseException ex) {
            long elapsed = elapsedMs(startNanos);
            int status = ex.getStatusCode().value();
            LlmErrorType errType = (status == 429) ? LlmErrorType.RATE_LIMITED : LlmErrorType.HTTP_ERROR;
            log.warn("Upstream LLM HTTP error: provider={} status={}", provider.getName(), status);
            return LlmResponse.failure(elapsed, errType, "Upstream HTTP status " + status);
        } catch (ResourceAccessException ex) {
            long elapsed = elapsedMs(startNanos);
            boolean timedOut = isTimeoutCause(ex, elapsed, timeout);
            LlmErrorType errType = timedOut ? LlmErrorType.TIMEOUT : LlmErrorType.HTTP_ERROR;
            log.warn("Upstream LLM transport error: provider={} type={}", provider.getName(), errType);
            return LlmResponse.failure(elapsed, errType, timedOut ? "Upstream request timed out" : "Upstream connection error");
        } catch (Exception ex) {
            long elapsed = elapsedMs(startNanos);
            if (isTimeoutCause(ex, elapsed, timeout)) {
                log.warn("Upstream LLM timeout: provider={}", provider.getName());
                return LlmResponse.failure(elapsed, LlmErrorType.TIMEOUT, "Upstream request timed out");
            }
            log.warn("Upstream LLM unexpected error: provider={} class={}", provider.getName(), ex.getClass().getSimpleName());
            return LlmResponse.failure(elapsed, LlmErrorType.HTTP_ERROR, "Upstream call failed");
        }
    }

    private LlmResponse parseSuccess(
            String rawBody, String pricingJson, LlmRequest request, long elapsedMs) {
        if (rawBody == null || rawBody.isBlank()) {
            return LlmResponse.failure(elapsedMs, LlmErrorType.MALFORMED_RESPONSE, "Empty response body");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return LlmResponse.failure(elapsedMs, LlmErrorType.MALFORMED_RESPONSE, "Missing choices in response");
            }
            JsonNode contentNode = choices.get(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return LlmResponse.failure(elapsedMs, LlmErrorType.MALFORMED_RESPONSE, "Missing message content");
            }
            String content = contentNode.asText();

            JsonNode usage = root.path("usage");
            int inputTokens = usage.hasNonNull("prompt_tokens")
                    ? usage.get("prompt_tokens").asInt()
                    : estimateInputTokens(request);
            int outputTokens = usage.hasNonNull("completion_tokens")
                    ? usage.get("completion_tokens").asInt()
                    : MockLlmClient.estimateTokens(content);
            BigDecimal cost = computeCost(pricingJson, inputTokens, outputTokens);

            return LlmResponse.ok(content, elapsedMs, inputTokens, outputTokens, cost);
        } catch (Exception ex) {
            return LlmResponse.failure(elapsedMs, LlmErrorType.MALFORMED_RESPONSE, "Invalid JSON in upstream response");
        }
    }

    private List<Map<String, String>> toWireMessages(List<LlmMessage> messages) {
        return messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content() == null ? "" : m.content()))
                .toList();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean isTimeoutCause(Throwable ex, long elapsedMs, Duration timeout) {
        Throwable curr = ex;
        while (curr != null) {
            if (curr instanceof HttpTimeoutException
                    || curr instanceof SocketTimeoutException
                    || curr instanceof TimeoutException) {
                return true;
            }
            String msg = curr.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return true;
                }
                if (curr instanceof java.io.IOException
                        && lower.contains("closed")
                        && elapsedMs >= timeout.toMillis()) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    private int estimateInputTokens(LlmRequest request) {
        int chars = 0;
        for (LlmMessage msg : request.messages()) {
            if (msg.content() != null) {
                chars += msg.content().length();
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

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
