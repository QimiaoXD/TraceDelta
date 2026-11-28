package io.tracedelta.runner;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("eval_result")
public class EvalResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long datasetCaseId;
    private String caseKey;
    private String caseHash;
    private String caseSnapshotJson;
    private String status;
    private String scoreConclusion;
    private Integer attemptCount;
    private Boolean hasInterruptedUnknown;
    private String outputText;
    private Long latencyMs;
    private Long ttftMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal estimatedCostUsd;
    private String errorType;
    private String errorMessage;
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getDatasetCaseId() { return datasetCaseId; }
    public void setDatasetCaseId(Long datasetCaseId) { this.datasetCaseId = datasetCaseId; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getCaseHash() { return caseHash; }
    public void setCaseHash(String caseHash) { this.caseHash = caseHash; }
    public String getCaseSnapshotJson() { return caseSnapshotJson; }
    public void setCaseSnapshotJson(String caseSnapshotJson) { this.caseSnapshotJson = caseSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getScoreConclusion() { return scoreConclusion; }
    public void setScoreConclusion(String scoreConclusion) { this.scoreConclusion = scoreConclusion; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Boolean getHasInterruptedUnknown() { return hasInterruptedUnknown; }
    public void setHasInterruptedUnknown(Boolean hasInterruptedUnknown) { this.hasInterruptedUnknown = hasInterruptedUnknown; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public Long getTtftMs() { return ttftMs; }
    public void setTtftMs(Long ttftMs) { this.ttftMs = ttftMs; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(BigDecimal estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
