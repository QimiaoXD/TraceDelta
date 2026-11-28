package io.tracedelta.target.http;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.auth.AesGcmEncryptor;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.target.CaseSnapshot;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetExecutionContext;
import io.tracedelta.trace.SensitiveDataSanitizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class HttpTargetService {

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH");
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};
    private static final String DEFAULT_SMOKE_INPUT = "{\"question\":\"你好，请介绍一下 TraceDelta\"}";

    private final HttpTargetMapper mapper;
    private final AesGcmEncryptor encryptor;
    private final SsrfGuard ssrfGuard;
    private final SensitiveDataSanitizer sanitizer;
    private final HttpTarget httpTarget;
    private final ObjectMapper objectMapper;

    public HttpTargetService(
            HttpTargetMapper mapper,
            AesGcmEncryptor encryptor,
            SsrfGuard ssrfGuard,
            SensitiveDataSanitizer sanitizer,
            HttpTarget httpTarget,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.encryptor = encryptor;
        this.ssrfGuard = ssrfGuard;
        this.sanitizer = sanitizer;
        this.httpTarget = httpTarget;
        this.objectMapper = objectMapper;
    }

    public List<HttpTargetView> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<HttpTargetEntity>().orderByDesc(HttpTargetEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public HttpTargetView getView(Long id) {
        return toView(getEntity(id));
    }

    public HttpTargetEntity getEntity(Long id) {
        HttpTargetEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "外部 HTTP 目标不存在: " + id);
        }
        return entity;
    }

    public HttpTargetView create(HttpTargetUpsertRequest req) {
        String name = normalizeRequired(req.name(), "名称不能为空");
        ensureNameUnique(name, null);
        String url = validateUrl(req.url());
        String method = validateMethod(req.httpMethod());
        long timeoutMs = validateTimeout(req.defaultTimeoutMs());
        validateJsonPath(req.responseJsonPath());

        LocalDateTime now = LocalDateTime.now();
        HttpTargetEntity entity = new HttpTargetEntity();
        entity.setName(name);
        entity.setDescription(trimToNull(req.description()));
        entity.setUrl(url);
        entity.setHttpMethod(method);
        applyHeaders(entity, req.headers(), false);
        entity.setBodyTemplate(trimToNull(req.bodyTemplate()));
        entity.setResponseJsonPath(trimToNull(req.responseJsonPath()));
        entity.setDefaultTimeoutMs(timeoutMs);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toView(entity);
    }

    public HttpTargetView update(Long id, HttpTargetUpsertRequest req) {
        HttpTargetEntity existing = getEntity(id);
        String name = normalizeRequired(req.name(), "名称不能为空");
        ensureNameUnique(name, id);
        String url = validateUrl(req.url());
        String method = validateMethod(req.httpMethod());
        long timeoutMs = validateTimeout(req.defaultTimeoutMs());
        validateJsonPath(req.responseJsonPath());

        existing.setName(name);
        existing.setDescription(trimToNull(req.description()));
        existing.setUrl(url);
        existing.setHttpMethod(method);
        if (req.headers() != null) {
            applyHeaders(existing, req.headers(), true);
        }
        existing.setBodyTemplate(trimToNull(req.bodyTemplate()));
        existing.setResponseJsonPath(trimToNull(req.responseJsonPath()));
        existing.setDefaultTimeoutMs(timeoutMs);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        return toView(existing);
    }

    public void delete(Long id) {
        getEntity(id);
        mapper.deleteById(id);
    }

    public HttpTargetSmokeTestView smokeTest(Long id, HttpTargetSmokeTestRequest req) {
        HttpTargetEntity entity = getEntity(id);
        String inputJson = (req == null || req.inputJson() == null || req.inputJson().isBlank())
                ? DEFAULT_SMOKE_INPUT
                : req.inputJson().trim();
        long timeoutMs = (req != null && req.timeoutMs() != null && req.timeoutMs() >= 1000L)
                ? Math.min(req.timeoutMs(), 120_000L)
                : entity.getDefaultTimeoutMs();

        TargetConfigSnapshot snapshot = TargetConfigSnapshot.forHttp(entity);
        CaseSnapshot smokeCase = new CaseSnapshot("smoke-test", "smoke", inputJson, null, null);
        HttpTarget.HttpInvocationOutcome outcome = httpTarget.invoke(
                new TargetExecutionContext(snapshot, smokeCase, 1, Duration.ofMillis(timeoutMs)));

        boolean ok = outcome.response().errorType() == LlmErrorType.NONE;
        String safePreview = sanitizer.sanitizeText(outcome.rawResponseBody(), 2048);
        return new HttpTargetSmokeTestView(
                ok,
                outcome.httpStatus(),
                outcome.response().latencyMs(),
                ok ? sanitizer.sanitizeText(outcome.response().content(), 4096) : null,
                safePreview,
                ok ? null : outcome.response().errorType().name(),
                ok ? null : sanitizer.sanitizeText(outcome.response().errorMessage(), 512));
    }

    private void applyHeaders(HttpTargetEntity entity, Map<String, String> incoming, boolean mergeExistingSensitive) {
        if (incoming == null || incoming.isEmpty()) {
            entity.setHeadersCiphertext(null);
            entity.setHeadersMaskedJson(null);
            return;
        }
        Map<String, String> previous = mergeExistingSensitive ? decryptQuietly(entity.getHeadersCiphertext()) : Map.of();
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim();
            String val = entry.getValue() == null ? "" : entry.getValue().trim();
            // On edit, if a sensitive header still holds the [REDACTED] placeholder or blank, preserve previous secret
            if (mergeExistingSensitive
                    && sanitizer.isSensitiveKey(key)
                    && (val.isEmpty() || SensitiveDataSanitizer.REDACTED.equals(val))
                    && previous.containsKey(key)) {
                clean.put(key, previous.get(key));
            } else if (!val.isEmpty()) {
                clean.put(key, val);
            }
        }
        if (clean.isEmpty()) {
            entity.setHeadersCiphertext(null);
            entity.setHeadersMaskedJson(null);
            return;
        }
        try {
            String plainJson = objectMapper.writeValueAsString(clean);
            entity.setHeadersCiphertext(encryptor.encrypt(plainJson));
            Map<String, String> masked = sanitizer.maskHeaders(clean);
            entity.setHeadersMaskedJson(objectMapper.writeValueAsString(masked));
        } catch (Exception ex) {
            throw new IllegalStateException("加密 HTTP 请求头失败", ex);
        }
    }

    private Map<String, String> decryptQuietly(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> map = objectMapper.readValue(encryptor.decrypt(ciphertext), STRING_MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private HttpTargetView toView(HttpTargetEntity entity) {
        Map<String, String> masked = Map.of();
        if (entity.getHeadersMaskedJson() != null && !entity.getHeadersMaskedJson().isBlank()) {
            try {
                Map<String, String> parsed = objectMapper.readValue(entity.getHeadersMaskedJson(), STRING_MAP_TYPE);
                if (parsed != null) {
                    masked = parsed;
                }
            } catch (Exception ignored) {
                masked = Map.of();
            }
        }
        return new HttpTargetView(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getUrl(),
                entity.getHttpMethod(),
                masked,
                entity.getBodyTemplate(),
                entity.getResponseJsonPath(),
                entity.getDefaultTimeoutMs(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String validateUrl(String rawUrl) {
        try {
            ssrfGuard.validateAndResolve(rawUrl);
            return rawUrl.trim();
        } catch (SsrfBlockedException ex) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, ex.getMessage());
        }
    }

    private String validateMethod(String rawMethod) {
        String method = (rawMethod == null || rawMethod.isBlank())
                ? "POST"
                : rawMethod.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的 HTTP 方法: " + method);
        }
        return method;
    }

    private long validateTimeout(Long timeoutMs) {
        long val = timeoutMs == null ? 30_000L : timeoutMs;
        if (val < 1_000L || val > 300_000L) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "defaultTimeoutMs 必须在 1000 到 300000 之间");
        }
        return val;
    }

    private void validateJsonPath(String jsonPath) {
        if (jsonPath != null && !jsonPath.isBlank() && !jsonPath.trim().startsWith("$")) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "responseJsonPath 必须以 $ 开头，例如 $.data.answer");
        }
    }

    private void ensureNameUnique(String name, Long excludeId) {
        LambdaQueryWrapper<HttpTargetEntity> query = new LambdaQueryWrapper<HttpTargetEntity>()
                .eq(HttpTargetEntity::getName, name);
        if (excludeId != null) {
            query.ne(HttpTargetEntity::getId, excludeId);
        }
        if (mapper.selectCount(query) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "已存在同名的外部 HTTP 目标: " + name);
        }
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, message);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
