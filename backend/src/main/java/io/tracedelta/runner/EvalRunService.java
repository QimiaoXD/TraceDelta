package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.dataset.DatasetCaseEntity;
import io.tracedelta.dataset.DatasetCaseMapper;
import io.tracedelta.dataset.DatasetEntity;
import io.tracedelta.dataset.DatasetMapper;
import io.tracedelta.dataset.DatasetVersionEntity;
import io.tracedelta.dataset.DatasetVersionMapper;
import io.tracedelta.provider.ProviderConfigEntity;
import io.tracedelta.provider.ProviderService;
import io.tracedelta.provider.ProviderType;
import io.tracedelta.target.CaseSnapshot;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetRegistry;
import io.tracedelta.target.TargetType;
import io.tracedelta.target.http.HttpTargetEntity;
import io.tracedelta.target.http.HttpTargetService;
import io.tracedelta.trace.TraceIdGenerator;
import io.tracedelta.trace.TraceSpanMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EvalRunService {

    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvalAttemptMapper attemptMapper;
    private final TraceSpanMapper traceSpanMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetCaseMapper caseMapper;
    private final ProviderService providerService;
    private final HttpTargetService httpTargetService;
    private final TargetRegistry targetRegistry;
    private final RunnerProperties properties;
    private final EvalRunExecutor runExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public EvalRunService(
            EvalRunMapper runMapper,
            EvalResultMapper resultMapper,
            EvalAttemptMapper attemptMapper,
            TraceSpanMapper traceSpanMapper,
            DatasetMapper datasetMapper,
            DatasetVersionMapper versionMapper,
            DatasetCaseMapper caseMapper,
            ProviderService providerService,
            HttpTargetService httpTargetService,
            TargetRegistry targetRegistry,
            RunnerProperties properties,
            EvalRunExecutor runExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.attemptMapper = attemptMapper;
        this.traceSpanMapper = traceSpanMapper;
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
        this.caseMapper = caseMapper;
        this.providerService = providerService;
        this.httpTargetService = httpTargetService;
        this.targetRegistry = targetRegistry;
        this.properties = properties;
        this.runExecutor = runExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public EvalRunDetailView createAndStart(CreateEvalRunRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请求不能为空");
        }
        DatasetEntity dataset = datasetMapper.selectById(request.datasetId());
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在: " + request.datasetId());
        }
        DatasetVersionEntity version = versionMapper.selectOne(
                new LambdaQueryWrapper<DatasetVersionEntity>()
                        .eq(DatasetVersionEntity::getDatasetId, dataset.getId())
                        .eq(DatasetVersionEntity::getVersionNo, request.datasetVersionNo()));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集版本不存在: v" + request.datasetVersionNo());
        }
        List<DatasetCaseEntity> cases = caseMapper.selectList(
                new LambdaQueryWrapper<DatasetCaseEntity>()
                        .eq(DatasetCaseEntity::getDatasetVersionId, version.getId())
                        .orderByAsc(DatasetCaseEntity::getId));
        if (cases.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "数据集版本不包含任何用例");
        }

        TargetType targetType = request.targetType() == null ? TargetType.PROMPT : request.targetType();
        targetRegistry.get(targetType);

        int concurrency = request.concurrency() == null ? properties.getDefaultConcurrency() : request.concurrency();
        if (concurrency < 1 || concurrency > properties.getGlobalMaxConcurrency()) {
            throw new BizException(
                    ErrorCode.VALIDATION_ERROR,
                    "concurrency 必须在 1 到 " + properties.getGlobalMaxConcurrency() + " 之间");
        }
        int maxAttempts = request.maxAttempts() == null ? properties.getDefaultMaxAttempts() : request.maxAttempts();
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "maxAttempts 必须在 1 到 10 之间");
        }

        Long providerId = null;
        Long httpTargetId = null;
        String targetDisplayName;
        String modelDisplay;
        long defaultTimeout = properties.getDefaultRequestTimeoutMs();
        TargetConfigSnapshot targetSnapshot;

        if (targetType == TargetType.HTTP) {
            if (request.httpTargetId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "HTTP 目标评测必须指定 httpTargetId");
            }
            HttpTargetEntity httpTarget = httpTargetService.getEntity(request.httpTargetId());
            httpTargetId = httpTarget.getId();
            targetDisplayName = httpTarget.getName();
            modelDisplay = httpTarget.getHttpMethod() + " " + summarizeUrl(httpTarget.getUrl());
            if (httpTarget.getDefaultTimeoutMs() != null) {
                defaultTimeout = httpTarget.getDefaultTimeoutMs();
            }
            targetSnapshot = TargetConfigSnapshot.forHttp(httpTarget);
        } else {
            if (request.providerId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "Prompt 目标评测必须指定 providerId");
            }
            ProviderConfigEntity provider = providerService.getEntity(request.providerId());
            providerId = provider.getId();
            targetDisplayName = provider.getName();
            modelDisplay = isBlank(request.model()) ? provider.getDefaultModel() : request.model().trim();
            targetSnapshot = new TargetConfigSnapshot(
                    targetType,
                    provider.getId(),
                    provider.getName(),
                    ProviderType.valueOf(provider.getProviderType()),
                    provider.getBaseUrl(),
                    modelDisplay,
                    provider.getPricingJson(),
                    provider.getMockConfigJson(),
                    isBlank(request.systemPrompt()) ? null : request.systemPrompt().trim(),
                    isBlank(request.userPromptTemplate()) ? "{{question}}" : request.userPromptTemplate());
        }

        long timeoutMs = request.requestTimeoutMs() == null ? defaultTimeout : request.requestTimeoutMs();
        if (timeoutMs < 1_000L || timeoutMs > 300_000L) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "requestTimeoutMs 必须在 1000 到 300000 之间");
        }
        double rateLimitPerSec = request.rateLimitPerSec() == null
                ? properties.getDefaultRateLimitPerSec()
                : request.rateLimitPerSec();
        if (rateLimitPerSec <= 0.0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "rateLimitPerSec 必须大于 0");
        }
        int rateLimitBurst = request.rateLimitBurst() == null
                ? properties.getDefaultRateLimitBurst()
                : request.rateLimitBurst();
        if (rateLimitBurst < 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "rateLimitBurst 必须大于等于 1");
        }
        if (request.maxCostUsd() != null && request.maxCostUsd().signum() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "maxCostUsd 必须大于 0");
        }
        if (request.maxTotalTokens() != null && request.maxTotalTokens() <= 0L) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "maxTotalTokens 必须大于 0");
        }

        String runName = isBlank(request.name())
                ? dataset.getName() + " v" + version.getVersionNo() + " · " + (targetType == TargetType.HTTP ? targetDisplayName : modelDisplay)
                : request.name().trim();

        String targetSnapshotJson = writeJson(targetSnapshot);
        final Long finalProviderId = providerId;
        final Long finalHttpTargetId = httpTargetId;
        final String finalTargetDisplayName = targetDisplayName;
        final String finalModelDisplay = modelDisplay;

        // Commit run + pending results BEFORE dispatching virtual threads so workers always read committed rows
        EvalRunEntity created = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            EvalRunEntity run = new EvalRunEntity();
            run.setName(runName);
            run.setRunType(EvalRunType.STANDARD.name());
            run.setStatus(EvalRunStatus.PENDING.name());
            run.setDatasetId(dataset.getId());
            run.setDatasetVersionId(version.getId());
            run.setDatasetNameSnapshot(dataset.getName());
            run.setDatasetVersionNo(version.getVersionNo());
            run.setTargetType(targetType.name());
            run.setProviderId(finalProviderId);
            run.setHttpTargetId(finalHttpTargetId);
            run.setProviderNameSnapshot(finalTargetDisplayName);
            run.setModelSnapshot(finalModelDisplay);
            run.setTargetConfigSnapshotJson(targetSnapshotJson);
            run.setConcurrency(concurrency);
            run.setMaxAttempts(maxAttempts);
            run.setRequestTimeoutMs(timeoutMs);
            run.setRateLimitPerSec(rateLimitPerSec);
            run.setRateLimitBurst(rateLimitBurst);
            run.setMaxCostUsd(request.maxCostUsd());
            run.setMaxTotalTokens(request.maxTotalTokens());
            run.setRetryBaseDelayMs(properties.getDefaultRetryBaseDelayMs());
            run.setRetryMaxDelayMs(properties.getDefaultRetryMaxDelayMs());
            run.setTotalCases(cases.size());
            run.setCompletedCases(0);
            run.setSucceededCases(0);
            run.setFailedCases(0);
            run.setSkippedCases(0);
            run.setTotalInputTokens(0L);
            run.setTotalOutputTokens(0L);
            run.setTotalCostUsd(BigDecimal.ZERO);
            run.setBudgetExceeded(false);
            run.setInterruptedAttemptCount(0);
            run.setRateLimitDegraded(false);
            run.setCancelRequested(false);
            run.setTraceIncomplete(false);
            run.setTraceDroppedCount(0);
            run.setCreatedAt(now);
            run.setUpdatedAt(now);
            runMapper.insert(run);

            for (DatasetCaseEntity c : cases) {
                CaseSnapshot cs = new CaseSnapshot(
                        c.getCaseKey(),
                        c.getCaseHash(),
                        c.getInputJson(),
                        c.getMessagesJson(),
                        c.getReferenceAnswer());
                EvalResultEntity result = new EvalResultEntity();
                result.setRunId(run.getId());
                result.setDatasetCaseId(c.getId());
                result.setCaseKey(c.getCaseKey());
                result.setCaseHash(c.getCaseHash());
                result.setCaseSnapshotJson(writeJson(cs));
                result.setTraceId(TraceIdGenerator.newTraceId());
                result.setStatus(EvalResultStatus.PENDING.name());
                result.setScoreConclusion(EvalScoreConclusion.UNSCORED.name());
                result.setAttemptCount(0);
                result.setHasInterruptedUnknown(false);
                result.setInputTokens(0);
                result.setOutputTokens(0);
                result.setEstimatedCostUsd(BigDecimal.ZERO);
                result.setVersion(0);
                result.setCreatedAt(now);
                result.setUpdatedAt(now);
                resultMapper.insert(result);
            }
            return run;
        });

        runExecutor.submitRun(created.getId());
        return EvalRunDetailView.fromEntity(created);
    }

    public List<EvalRunSummaryView> listRuns(EvalRunStatus statusFilter) {
        LambdaQueryWrapper<EvalRunEntity> query = new LambdaQueryWrapper<EvalRunEntity>()
                .orderByDesc(EvalRunEntity::getId);
        if (statusFilter != null) {
            query.eq(EvalRunEntity::getStatus, statusFilter.name());
        }
        return runMapper.selectList(query).stream()
                .map(EvalRunSummaryView::fromEntity)
                .toList();
    }

    public EvalRunDetailView getRunDetail(Long runId) {
        return EvalRunDetailView.fromEntity(getRunOrThrow(runId));
    }

    public List<EvalResultView> listResults(Long runId, EvalResultStatus statusFilter, String queryText) {
        getRunOrThrow(runId);
        LambdaQueryWrapper<EvalResultEntity> wrapper = new LambdaQueryWrapper<EvalResultEntity>()
                .eq(EvalResultEntity::getRunId, runId)
                .orderByAsc(EvalResultEntity::getId);
        if (statusFilter != null) {
            wrapper.eq(EvalResultEntity::getStatus, statusFilter.name());
        }
        if (!isBlank(queryText)) {
            wrapper.like(EvalResultEntity::getCaseKey, queryText.trim());
        }
        return resultMapper.selectList(wrapper).stream()
                .map(EvalResultView::fromEntity)
                .toList();
    }

    public List<EvalAttemptView> listAttempts(Long runId, Long resultId) {
        getRunOrThrow(runId);
        EvalResultEntity result = resultMapper.selectById(resultId);
        if (result == null || !runId.equals(result.getRunId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测用例结果不存在: " + resultId);
        }
        return attemptMapper.selectList(
                        new LambdaQueryWrapper<EvalAttemptEntity>()
                                .eq(EvalAttemptEntity::getEvalResultId, resultId)
                                .orderByAsc(EvalAttemptEntity::getAttemptNo))
                .stream()
                .map(EvalAttemptView::fromEntity)
                .toList();
    }

    public EvalRunDetailView cancelRun(Long runId) {
        EvalRunEntity run = getRunOrThrow(runId);
        EvalRunStatus current = EvalRunStatus.valueOf(run.getStatus());
        if (current.isTerminal()) {
            throw new BizException(ErrorCode.INVALID_STATE, "评测任务已处于终态，无法取消: " + current.name());
        }
        runMapper.markCancelRequested(runId, LocalDateTime.now());
        runExecutor.requestCancel(runId);
        return EvalRunDetailView.fromEntity(getRunOrThrow(runId));
    }

    public void deleteRun(Long runId) {
        transactionTemplate.executeWithoutResult(status -> {
            EvalRunEntity run = getRunOrThrow(runId);
            EvalRunStatus current = EvalRunStatus.valueOf(run.getStatus());
            if (!current.isTerminal()) {
                throw new BizException(
                        ErrorCode.INVALID_STATE,
                        "运行中的评测任务无法直接删除，请先取消并等待其进入终态: " + current.name());
            }
            traceSpanMapper.deleteByRunId(runId);
            attemptMapper.delete(new LambdaQueryWrapper<EvalAttemptEntity>()
                    .eq(EvalAttemptEntity::getRunId, runId));
            resultMapper.delete(new LambdaQueryWrapper<EvalResultEntity>()
                    .eq(EvalResultEntity::getRunId, runId));
            runMapper.deleteById(runId);
        });
    }

    private EvalRunEntity getRunOrThrow(Long runId) {
        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测任务不存在: " + runId);
        }
        return run;
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize snapshot JSON", ex);
        }
    }

    private static String summarizeUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String hostPort = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String summary = hostPort + path;
            return summary.length() <= 100 ? summary : summary.substring(0, 100);
        } catch (Exception ex) {
            return rawUrl == null ? "" : (rawUrl.length() <= 100 ? rawUrl : rawUrl.substring(0, 100));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
