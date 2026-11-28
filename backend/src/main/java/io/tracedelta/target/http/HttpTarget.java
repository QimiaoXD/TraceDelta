package io.tracedelta.target.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.auth.AesGcmEncryptor;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.provider.llm.LlmResponse;
import io.tracedelta.target.CaseSnapshot;
import io.tracedelta.target.EvalTarget;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetExecutionContext;
import io.tracedelta.target.TargetExecutionResult;
import io.tracedelta.target.TargetType;
import io.tracedelta.trace.SensitiveDataSanitizer;
import io.tracedelta.trace.SpanDraft;
import io.tracedelta.trace.SpanKind;
import io.tracedelta.trace.SpanStatus;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HttpTarget implements EvalTarget {

    private static final Logger log = LoggerFactory.getLogger(HttpTarget.class);
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "connection", "content-length", "expect", "upgrade");
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final HttpTargetMapper httpTargetMapper;
    private final AesGcmEncryptor encryptor;
    private final SsrfGuard ssrfGuard;
    private final HttpRequestTemplateRenderer templateRenderer;
    private final JsonPathExtractor jsonPathExtractor;
    private final SensitiveDataSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final HttpClient sharedHttpClient;

    public HttpTarget(
            HttpTargetMapper httpTargetMapper,
            AesGcmEncryptor encryptor,
            SsrfGuard ssrfGuard,
            HttpRequestTemplateRenderer templateRenderer,
            JsonPathExtractor jsonPathExtractor,
            SensitiveDataSanitizer sanitizer,
            ObjectMapper objectMapper) {
        this.httpTargetMapper = httpTargetMapper;
        this.encryptor = encryptor;
        this.ssrfGuard = ssrfGuard;
        this.templateRenderer = templateRenderer;
        this.jsonPathExtractor = jsonPathExtractor;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.sharedHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PreDestroy
    void closeClient() {
        sharedHttpClient.close();
    }

    @Override
    public TargetType type() {
        return TargetType.HTTP;
    }

    @Override
    public String rateLimitKey(TargetConfigSnapshot config) {
        return "http_target:" + config.httpTargetId();
    }

    @Override
    public TargetExecutionResult execute(TargetExecutionContext context) {
        HttpInvocationOutcome outcome = invoke(context);
        return new TargetExecutionResult(outcome.response(), outcome.spans());
    }

    HttpInvocationOutcome invoke(TargetExecutionContext context) {
        TargetConfigSnapshot cfg = context.targetConfig();
        CaseSnapshot cs = context.caseSnapshot();
        String method = cfg.httpMethod() == null || cfg.httpMethod().isBlank()
                ? "POST"
                : cfg.httpMethod().trim().toUpperCase(Locale.ROOT);
        List<SpanDraft> spans = new ArrayList<>(2);

        String renderedBody = null;
        if (cfg.httpBodyTemplate() != null && !cfg.httpBodyTemplate().isBlank() && !"GET".equals(method)) {
            long renderStart = System.currentTimeMillis();
            renderedBody = templateRenderer.renderBody(cfg.httpBodyTemplate(), cs.inputJson());
            long renderEnd = Math.max(renderStart, System.currentTimeMillis());
            Map<String, Object> renderAttrs = new LinkedHashMap<>();
            renderAttrs.put("tracedelta.http.method", method);
            renderAttrs.put("tracedelta.template.length", cfg.httpBodyTemplate().length());
            spans.add(new SpanDraft(
                    "http.template.render",
                    SpanKind.TEMPLATE_RENDER,
                    SpanStatus.OK,
                    renderStart,
                    renderEnd,
                    null,
                    null,
                    null,
                    null,
                    cs.inputJson(),
                    renderedBody,
                    renderAttrs,
                    null,
                    null));
        }

        long callStart = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        URI uri;
        try {
            uri = ssrfGuard.validateAndResolve(cfg.httpUrl());
        } catch (SsrfBlockedException ex) {
            long elapsed = elapsedMs(startNanos);
            LlmResponse failure = LlmResponse.failure(elapsed, LlmErrorType.SSRF_BLOCKED, ex.getMessage());
            spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, Map.of(), callStart, System.currentTimeMillis()));
            return new HttpInvocationOutcome(failure, null, null, spans);
        }

        Map<String, String> decryptedHeaders;
        try {
            HttpTargetEntity live = cfg.httpTargetId() == null ? null : httpTargetMapper.selectById(cfg.httpTargetId());
            if (live == null) {
                long elapsed = elapsedMs(startNanos);
                LlmResponse failure = LlmResponse.failure(
                        elapsed, LlmErrorType.MALFORMED_RESPONSE, "HTTP 目标配置不存在或已被删除: " + cfg.httpTargetId());
                spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, Map.of(), callStart, System.currentTimeMillis()));
                return new HttpInvocationOutcome(failure, null, null, spans);
            }
            decryptedHeaders = decryptHeaders(live.getHeadersCiphertext());
        } catch (Exception ex) {
            long elapsed = elapsedMs(startNanos);
            LlmResponse failure = LlmResponse.failure(
                    elapsed, LlmErrorType.MALFORMED_RESPONSE, "无法加载 HTTP 目标请求头: " + ex.getMessage());
            spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, Map.of(), callStart, System.currentTimeMillis()));
            return new HttpInvocationOutcome(failure, null, null, spans);
        }

        Map<String, String> maskedHeaders = sanitizer.maskHeaders(decryptedHeaders);
        Duration timeout = context.timeout() == null ? Duration.ofSeconds(30) : context.timeout();

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout);

            boolean hasContentType = false;
            for (Map.Entry<String, String> entry : decryptedHeaders.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                String lower = key.trim().toLowerCase(Locale.ROOT);
                if (RESTRICTED_HEADERS.contains(lower)) {
                    continue;
                }
                if ("content-type".equals(lower)) {
                    hasContentType = true;
                }
                reqBuilder.header(key.trim(), entry.getValue() == null ? "" : entry.getValue());
            }

            HttpRequest.BodyPublisher publisher = (renderedBody == null || "GET".equals(method))
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(renderedBody, StandardCharsets.UTF_8);
            if (renderedBody != null && !"GET".equals(method) && !hasContentType) {
                reqBuilder.header("Content-Type", "application/json");
            }
            reqBuilder.method(method, publisher);

            HttpResponse<String> httpResponse = sharedHttpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = elapsedMs(startNanos);
            int statusCode = httpResponse.statusCode();
            String rawBody = httpResponse.body();

            LlmResponse llmResponse;
            if (statusCode >= 300 && statusCode < 400) {
                llmResponse = LlmResponse.failure(
                        elapsed,
                        LlmErrorType.SSRF_BLOCKED,
                        "重定向已被 SSRF 策略拦截 (HTTP " + statusCode + ")");
            } else if (statusCode == 429) {
                llmResponse = LlmResponse.failure(elapsed, LlmErrorType.RATE_LIMITED, "Upstream HTTP status 429");
            } else if (statusCode >= 200 && statusCode < 300) {
                if (cfg.httpResponseJsonPath() == null || cfg.httpResponseJsonPath().isBlank()) {
                    llmResponse = LlmResponse.ok(rawBody == null ? "" : rawBody, elapsed, 0, 0, BigDecimal.ZERO);
                } else {
                    Optional<String> extracted = jsonPathExtractor.extract(rawBody, cfg.httpResponseJsonPath());
                    if (extracted.isPresent()) {
                        llmResponse = LlmResponse.ok(extracted.get(), elapsed, 0, 0, BigDecimal.ZERO);
                    } else {
                        llmResponse = LlmResponse.failure(
                                elapsed,
                                LlmErrorType.MALFORMED_RESPONSE,
                                "JSONPath 未匹配到有效值: " + cfg.httpResponseJsonPath());
                    }
                }
            } else {
                llmResponse = LlmResponse.failure(elapsed, LlmErrorType.HTTP_ERROR, "Upstream HTTP status " + statusCode);
            }

            long callEnd = Math.max(callStart, System.currentTimeMillis());
            spans.add(buildHttpSpan(cfg, method, statusCode, renderedBody, rawBody, llmResponse, maskedHeaders, callStart, callEnd));
            return new HttpInvocationOutcome(llmResponse, statusCode, rawBody, spans);
        } catch (HttpTimeoutException ex) {
            long elapsed = elapsedMs(startNanos);
            LlmResponse failure = LlmResponse.failure(elapsed, LlmErrorType.TIMEOUT, "HTTP 目标请求超时");
            spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, maskedHeaders, callStart, System.currentTimeMillis()));
            return new HttpInvocationOutcome(failure, null, null, spans);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long elapsed = elapsedMs(startNanos);
            LlmResponse failure = LlmResponse.failure(elapsed, LlmErrorType.HTTP_ERROR, "HTTP 请求被中断");
            spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, maskedHeaders, callStart, System.currentTimeMillis()));
            return new HttpInvocationOutcome(failure, null, null, spans);
        } catch (Exception ex) {
            long elapsed = elapsedMs(startNanos);
            log.warn("HttpTarget call failed: targetId={} err={}", cfg.httpTargetId(), ex.getClass().getSimpleName());
            LlmResponse failure = LlmResponse.failure(elapsed, LlmErrorType.HTTP_ERROR, "HTTP 调用失败: " + ex.getMessage());
            spans.add(buildHttpSpan(cfg, method, null, renderedBody, null, failure, maskedHeaders, callStart, System.currentTimeMillis()));
            return new HttpInvocationOutcome(failure, null, null, spans);
        }
    }

    private Map<String, String> decryptHeaders(String ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.isBlank()) {
            return Map.of();
        }
        String json = encryptor.decrypt(ciphertext);
        Map<String, String> parsed = objectMapper.readValue(json, STRING_MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    private SpanDraft buildHttpSpan(
            TargetConfigSnapshot cfg,
            String method,
            Integer statusCode,
            String renderedBody,
            String rawBody,
            LlmResponse response,
            Map<String, String> maskedHeaders,
            long startMs,
            long endMs) {
        boolean ok = response.errorType() == LlmErrorType.NONE;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("http.request.method", method);
        attrs.put("url.full", cfg.httpUrl());
        if (statusCode != null) {
            attrs.put("http.response.status_code", statusCode);
        }
        if (cfg.httpTargetId() != null) {
            attrs.put("tracedelta.http.target_id", cfg.httpTargetId());
        }
        if (cfg.httpTargetName() != null) {
            attrs.put("tracedelta.http.target_name", cfg.httpTargetName());
        }
        if (cfg.httpResponseJsonPath() != null && !cfg.httpResponseJsonPath().isBlank()) {
            attrs.put("tracedelta.http.response_json_path", cfg.httpResponseJsonPath());
        }
        if (!maskedHeaders.isEmpty()) {
            attrs.put("http.request.headers", maskedHeaders);
        }

        return new SpanDraft(
                "http.client",
                SpanKind.HTTP_CALL,
                ok ? SpanStatus.OK : SpanStatus.ERROR,
                startMs,
                Math.max(startMs, endMs),
                null,
                0,
                0,
                BigDecimal.ZERO,
                renderedBody,
                ok ? response.content() : rawBody,
                attrs,
                ok ? null : response.errorType().name(),
                ok ? null : response.errorMessage());
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    record HttpInvocationOutcome(
            LlmResponse response,
            Integer httpStatus,
            String rawResponseBody,
            List<SpanDraft> spans) {}
}
