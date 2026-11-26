package io.tracedelta.provider;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("provider_config")
public class ProviderConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String providerType;
    private String baseUrl;
    private String apiKeyCiphertext;
    private String apiKeyMask;
    private String defaultModel;
    private String pricingJson;
    private String mockConfigJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public void setApiKeyCiphertext(String apiKeyCiphertext) { this.apiKeyCiphertext = apiKeyCiphertext; }
    public String getApiKeyMask() { return apiKeyMask; }
    public void setApiKeyMask(String apiKeyMask) { this.apiKeyMask = apiKeyMask; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getPricingJson() { return pricingJson; }
    public void setPricingJson(String pricingJson) { this.pricingJson = pricingJson; }
    public String getMockConfigJson() { return mockConfigJson; }
    public void setMockConfigJson(String mockConfigJson) { this.mockConfigJson = mockConfigJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
