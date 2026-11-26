package io.tracedelta.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.auth.AesGcmEncryptor;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.provider.llm.MockProfile;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProviderService {

    private final ProviderConfigMapper providerConfigMapper;
    private final AesGcmEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public ProviderService(
            ProviderConfigMapper providerConfigMapper,
            AesGcmEncryptor encryptor,
            ObjectMapper objectMapper) {
        this.providerConfigMapper = providerConfigMapper;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    public ProviderView create(ProviderUpsertRequest request) {
        validateBasicFields(request);
        boolean hasKey = !isBlank(request.apiKey());
        if (request.providerType() == ProviderType.OPENAI_COMPATIBLE) {
            if (isBlank(request.baseUrl())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "OPENAI_COMPATIBLE 渠道必须填写 baseUrl");
            }
            if (!hasKey) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "OPENAI_COMPATIBLE 渠道创建时必须填写 apiKey");
            }
        }

        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setName(request.name().trim());
        entity.setProviderType(request.providerType().name());
        entity.setBaseUrl(normalizeNullable(request.baseUrl()));
        if (hasKey) {
            String key = request.apiKey().trim();
            entity.setApiKeyCiphertext(encryptor.encrypt(key));
            entity.setApiKeyMask(maskApiKey(key));
        }
        entity.setDefaultModel(request.defaultModel().trim());
        entity.setPricingJson(request.pricingJson());
        entity.setMockConfigJson(request.mockConfigJson());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        providerConfigMapper.insert(entity);
        return ProviderView.fromEntity(entity);
    }

    public ProviderView update(Long id, ProviderUpsertRequest request) {
        ProviderConfigEntity existing = getEntity(id);
        validateBasicFields(request);

        boolean hasNewKey = !isBlank(request.apiKey());
        boolean hasExistingKey = !isBlank(existing.getApiKeyCiphertext());
        if (request.providerType() == ProviderType.OPENAI_COMPATIBLE) {
            if (isBlank(request.baseUrl())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "OPENAI_COMPATIBLE 渠道必须填写 baseUrl");
            }
            if (!hasNewKey && !hasExistingKey) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "OPENAI_COMPATIBLE 渠道必须配置 apiKey");
            }
        }

        existing.setName(request.name().trim());
        existing.setProviderType(request.providerType().name());
        existing.setBaseUrl(normalizeNullable(request.baseUrl()));
        if (hasNewKey) {
            String key = request.apiKey().trim();
            existing.setApiKeyCiphertext(encryptor.encrypt(key));
            existing.setApiKeyMask(maskApiKey(key));
        }
        existing.setDefaultModel(request.defaultModel().trim());
        existing.setPricingJson(request.pricingJson());
        existing.setMockConfigJson(request.mockConfigJson());
        existing.setUpdatedAt(LocalDateTime.now());

        providerConfigMapper.updateById(existing);
        return ProviderView.fromEntity(existing);
    }

    public List<ProviderView> list() {
        return providerConfigMapper.selectList(
                        new LambdaQueryWrapper<ProviderConfigEntity>()
                                .orderByDesc(ProviderConfigEntity::getId))
                .stream()
                .map(ProviderView::fromEntity)
                .toList();
    }

    public ProviderView get(Long id) {
        return ProviderView.fromEntity(getEntity(id));
    }

    public void delete(Long id) {
        int deleted = providerConfigMapper.deleteById(id);
        if (deleted == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型渠道不存在: " + id);
        }
    }

    public ProviderConfigEntity getEntity(Long id) {
        ProviderConfigEntity entity = providerConfigMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型渠道不存在: " + id);
        }
        return entity;
    }

    public String decryptApiKey(ProviderConfigEntity entity) {
        if (entity == null || isBlank(entity.getApiKeyCiphertext())) {
            return null;
        }
        return encryptor.decrypt(entity.getApiKeyCiphertext());
    }

    private void validateBasicFields(ProviderUpsertRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请求不能为空");
        }
        if (isBlank(request.name())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "渠道名称不能为空");
        }
        if (request.providerType() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "渠道类型不能为空");
        }
        if (isBlank(request.defaultModel())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "默认模型不能为空");
        }
        if (!isBlank(request.baseUrl())) {
            validateHttpUrl(request.baseUrl().trim());
        }
        if (!isBlank(request.pricingJson())) {
            validatePricingJson(request.pricingJson());
        }
        if (!isBlank(request.mockConfigJson())) {
            validateMockConfigJson(request.mockConfigJson());
        }
    }

    private void validateHttpUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "baseUrl 必须是合法的 http/https 地址");
            }
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "baseUrl 必须是合法的 http/https 地址");
        }
    }

    private void validatePricingJson(String raw) {
        JsonNode root = parseJsonObject(raw, "pricingJson");
        validateNonNegativeNumber(root, "inputPer1k", "pricingJson");
        validateNonNegativeNumber(root, "outputPer1k", "pricingJson");
    }

    private void validateMockConfigJson(String raw) {
        JsonNode root = parseJsonObject(raw, "mockConfigJson");
        if (root.hasNonNull("profile")) {
            try {
                MockProfile.fromName(root.get("profile").asText());
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "mockConfigJson 中的 profile 不受支持");
            }
        }
        if (root.hasNonNull("latencyMs")) {
            if (!root.get("latencyMs").isNumber() || root.get("latencyMs").asLong() < 0) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "mockConfigJson 中的 latencyMs 必须为非负数");
            }
        }
        if (root.hasNonNull("failRate")) {
            if (!root.get("failRate").isNumber()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "mockConfigJson 中的 failRate 必须是数字");
            }
            double rate = root.get("failRate").asDouble();
            if (rate < 0.0 || rate > 1.0) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "mockConfigJson 中的 failRate 必须在 [0, 1] 之间");
            }
        }
    }

    private JsonNode parseJsonObject(String raw, String fieldName) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, fieldName + " 必须是合法 JSON 对象");
            }
            return node;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, fieldName + " 不是合法 JSON");
        }
    }

    private void validateNonNegativeNumber(JsonNode root, String key, String fieldName) {
        if (root.hasNonNull(key)) {
            JsonNode value = root.get(key);
            if (!value.isNumber() || value.decimalValue().signum() < 0) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, fieldName + "." + key + " 必须为非负数字");
            }
        }
    }

    private static String maskApiKey(String key) {
        if (key.length() >= 8) {
            return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
        }
        return "****";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeNullable(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
