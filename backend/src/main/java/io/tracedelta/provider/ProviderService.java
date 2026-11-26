package io.tracedelta.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.tracedelta.auth.AesGcmEncryptor;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProviderService {

    private final ProviderConfigMapper providerConfigMapper;
    private final AesGcmEncryptor encryptor;

    public ProviderService(ProviderConfigMapper providerConfigMapper, AesGcmEncryptor encryptor) {
        this.providerConfigMapper = providerConfigMapper;
        this.encryptor = encryptor;
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

    private static void validateBasicFields(ProviderUpsertRequest request) {
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
