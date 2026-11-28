package io.tracedelta.target.http;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("http_target")
public class HttpTargetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String url;
    private String httpMethod;
    private String headersCiphertext;
    private String headersMaskedJson;
    private String bodyTemplate;
    private String responseJsonPath;
    private Long defaultTimeoutMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getHeadersCiphertext() { return headersCiphertext; }
    public void setHeadersCiphertext(String headersCiphertext) { this.headersCiphertext = headersCiphertext; }
    public String getHeadersMaskedJson() { return headersMaskedJson; }
    public void setHeadersMaskedJson(String headersMaskedJson) { this.headersMaskedJson = headersMaskedJson; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public String getResponseJsonPath() { return responseJsonPath; }
    public void setResponseJsonPath(String responseJsonPath) { this.responseJsonPath = responseJsonPath; }
    public Long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(Long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
