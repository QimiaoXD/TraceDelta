package io.tracedelta.runner;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("eval_run")
public class EvalRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String runType;
    private String status;
    private String statusReason;
    private Long datasetId;
    private Long datasetVersionId;
    private String datasetNameSnapshot;
    private Integer datasetVersionNo;
    private String targetType;
    private Long providerId;
    private Long httpTargetId;
    private String providerNameSnapshot;
    private String modelSnapshot;
    private String targetConfigSnapshotJson;
    private Integer concurrency;
    private Integer maxAttempts;
    private Long requestTimeoutMs;
    private Double rateLimitPerSec;
    private Integer rateLimitBurst;
    private BigDecimal maxCostUsd;
    private Long maxTotalTokens;
    private Long retryBaseDelayMs;
    private Long retryMaxDelayMs;
    private Integer totalCases;
    private Integer completedCases;
    private Integer succeededCases;
    private Integer failedCases;
    private Integer skippedCases;
    private Long totalInputTokens;
    private Long totalOutputTokens;
    private BigDecimal totalCostUsd;
    private Long avgLatencyMs;
    private Long p95LatencyMs;
    private Boolean budgetExceeded;
    private String budgetExceededReason;
    private Integer interruptedAttemptCount;
    private Boolean rateLimitDegraded;
    private Boolean cancelRequested;
    private Boolean traceIncomplete;
    private Integer traceDroppedCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public Long getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(Long datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public String getDatasetNameSnapshot() { return datasetNameSnapshot; }
    public void setDatasetNameSnapshot(String datasetNameSnapshot) { this.datasetNameSnapshot = datasetNameSnapshot; }
    public Integer getDatasetVersionNo() { return datasetVersionNo; }
    public void setDatasetVersionNo(Integer datasetVersionNo) { this.datasetVersionNo = datasetVersionNo; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getProviderId() { return providerId; }
    public void setProviderId(Long providerId) { this.providerId = providerId; }
    public Long getHttpTargetId() { return httpTargetId; }
    public void setHttpTargetId(Long httpTargetId) { this.httpTargetId = httpTargetId; }
    public String getProviderNameSnapshot() { return providerNameSnapshot; }
    public void setProviderNameSnapshot(String providerNameSnapshot) { this.providerNameSnapshot = providerNameSnapshot; }
    public String getModelSnapshot() { return modelSnapshot; }
    public void setModelSnapshot(String modelSnapshot) { this.modelSnapshot = modelSnapshot; }
    public String getTargetConfigSnapshotJson() { return targetConfigSnapshotJson; }
    public void setTargetConfigSnapshotJson(String targetConfigSnapshotJson) { this.targetConfigSnapshotJson = targetConfigSnapshotJson; }
    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer concurrency) { this.concurrency = concurrency; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Long getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(Long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public Double getRateLimitPerSec() { return rateLimitPerSec; }
    public void setRateLimitPerSec(Double rateLimitPerSec) { this.rateLimitPerSec = rateLimitPerSec; }
    public Integer getRateLimitBurst() { return rateLimitBurst; }
    public void setRateLimitBurst(Integer rateLimitBurst) { this.rateLimitBurst = rateLimitBurst; }
    public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    public void setMaxCostUsd(BigDecimal maxCostUsd) { this.maxCostUsd = maxCostUsd; }
    public Long getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(Long maxTotalTokens) { this.maxTotalTokens = maxTotalTokens; }
    public Long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public void setRetryBaseDelayMs(Long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }
    public Long getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public void setRetryMaxDelayMs(Long retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Integer getCompletedCases() { return completedCases; }
    public void setCompletedCases(Integer completedCases) { this.completedCases = completedCases; }
    public Integer getSucceededCases() { return succeededCases; }
    public void setSucceededCases(Integer succeededCases) { this.succeededCases = succeededCases; }
    public Integer getFailedCases() { return failedCases; }
    public void setFailedCases(Integer failedCases) { this.failedCases = failedCases; }
    public Integer getSkippedCases() { return skippedCases; }
    public void setSkippedCases(Integer skippedCases) { this.skippedCases = skippedCases; }
    public Long getTotalInputTokens() { return totalInputTokens; }
    public void setTotalInputTokens(Long totalInputTokens) { this.totalInputTokens = totalInputTokens; }
    public Long getTotalOutputTokens() { return totalOutputTokens; }
    public void setTotalOutputTokens(Long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; }
    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal totalCostUsd) { this.totalCostUsd = totalCostUsd; }
    public Long getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Long avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public Long getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(Long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public Boolean getBudgetExceeded() { return budgetExceeded; }
    public void setBudgetExceeded(Boolean budgetExceeded) { this.budgetExceeded = budgetExceeded; }
    public String getBudgetExceededReason() { return budgetExceededReason; }
    public void setBudgetExceededReason(String budgetExceededReason) { this.budgetExceededReason = budgetExceededReason; }
    public Integer getInterruptedAttemptCount() { return interruptedAttemptCount; }
    public void setInterruptedAttemptCount(Integer interruptedAttemptCount) { this.interruptedAttemptCount = interruptedAttemptCount; }
    public Boolean getRateLimitDegraded() { return rateLimitDegraded; }
    public void setRateLimitDegraded(Boolean rateLimitDegraded) { this.rateLimitDegraded = rateLimitDegraded; }
    public Boolean getCancelRequested() { return cancelRequested; }
    public void setCancelRequested(Boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public Boolean getTraceIncomplete() { return traceIncomplete; }
    public void setTraceIncomplete(Boolean traceIncomplete) { this.traceIncomplete = traceIncomplete; }
    public Integer getTraceDroppedCount() { return traceDroppedCount; }
    public void setTraceDroppedCount(Integer traceDroppedCount) { this.traceDroppedCount = traceDroppedCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
