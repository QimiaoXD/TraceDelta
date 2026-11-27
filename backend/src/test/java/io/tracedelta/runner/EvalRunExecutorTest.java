package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.dataset.DatasetCaseInput;
import io.tracedelta.dataset.DatasetEntity;
import io.tracedelta.dataset.DatasetService;
import io.tracedelta.dataset.DatasetVersionEntity;
import io.tracedelta.provider.ProviderService;
import io.tracedelta.provider.ProviderType;
import io.tracedelta.provider.ProviderUpsertRequest;
import io.tracedelta.provider.ProviderView;
import io.tracedelta.target.TargetType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EvalRunExecutorTest {

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private EvalRunService evalRunService;

    @Autowired
    private EvalRunExecutor evalRunExecutor;

    @Autowired
    private EvalResultMapper evalResultMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private DatasetVersionEntity createDataset(String name, int count) throws Exception {
        DatasetEntity ds = datasetService.create(name, "eval engine test");
        List<DatasetCaseInput> cases = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String key = String.format("case-%03d", i);
            cases.add(new DatasetCaseInput(
                    key,
                    objectMapper.readTree("{\"question\":\"Question number " + i + " with some extra text for tokens\"}"),
                    null,
                    "Reference answer for case " + i + " with deterministic output",
                    null,
                    List.of(),
                    List.of("m2")));
        }
        return datasetService.importVersion(ds.getId(), cases, "v1");
    }

    private ProviderView createMockProvider(String name, String defaultModel, String mockConfigJson, String pricingJson) {
        return providerService.create(new ProviderUpsertRequest(
                name,
                ProviderType.MOCK,
                null,
                null,
                defaultModel,
                pricingJson,
                mockConfigJson));
    }

    private EvalRunDetailView awaitTerminal(Long runId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            EvalRunDetailView detail = evalRunService.getRunDetail(runId);
            if (EvalRunStatus.valueOf(detail.status()).isTerminal()) {
                return detail;
            }
            Thread.sleep(25);
        }
        return evalRunService.getRunDetail(runId);
    }

    @Test
    void concurrentRunWith200MockGoodCasesCompletesWithAccurateAggregates() throws Exception {
        DatasetVersionEntity version = createDataset("ds-200", 200);
        ProviderView provider = createMockProvider(
                "mock-good-200",
                "mock-good",
                "{\"profile\":\"mock-good\",\"seed\":7,\"latencyMs\":0}",
                "{\"inputPer1k\":0.01,\"outputPer1k\":0.02}");

        EvalRunDetailView created = evalRunService.createAndStart(new CreateEvalRunRequest(
                "200-case-benchmark",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                "Be concise",
                "{{question}}",
                16,
                3,
                10_000L,
                1000.0,
                1000,
                null,
                null));

        EvalRunDetailView finished = awaitTerminal(created.id(), Duration.ofSeconds(15));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.COMPLETED.name());
        assertThat(finished.totalCases()).isEqualTo(200);
        assertThat(finished.completedCases()).isEqualTo(200);
        assertThat(finished.succeededCases()).isEqualTo(200);
        assertThat(finished.failedCases()).isEqualTo(0);
        assertThat(finished.skippedCases()).isEqualTo(0);
        assertThat(finished.totalInputTokens()).isGreaterThan(0L);
        assertThat(finished.totalOutputTokens()).isGreaterThan(0L);
        assertThat(finished.totalCostUsd()).isGreaterThan(BigDecimal.ZERO);
        assertThat(finished.p95LatencyMs()).isNotNull();

        List<EvalResultView> results = evalRunService.listResults(created.id(), null, null);
        assertThat(results).hasSize(200);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(EvalResultStatus.EXEC_SUCCEEDED.name());
            assertThat(r.scoreConclusion()).isEqualTo(EvalScoreConclusion.UNSCORED.name());
            assertThat(r.attemptCount()).isEqualTo(1);
        });
    }

    @Test
    void mockFlakyRetriesAndRecordsAllAttempts() throws Exception {
        DatasetVersionEntity version = createDataset("ds-flaky", 6);
        ProviderView provider = createMockProvider(
                "mock-flaky-100",
                "mock-flaky",
                "{\"profile\":\"mock-flaky\",\"seed\":42,\"latencyMs\":0,\"failRate\":1.0}",
                null);

        // maxAttempts=3: mock-flaky fails on attempt 1 & 2, always succeeds on attempt 3
        EvalRunDetailView run3 = evalRunService.createAndStart(new CreateEvalRunRequest(
                "flaky-recover-3",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                null,
                "{{question}}",
                4,
                3,
                10_000L,
                1000.0,
                1000,
                null,
                null));

        EvalRunDetailView finished3 = awaitTerminal(run3.id(), Duration.ofSeconds(10));
        assertThat(finished3.status()).isEqualTo(EvalRunStatus.COMPLETED.name());
        List<EvalResultView> results3 = evalRunService.listResults(run3.id(), null, null);
        assertThat(results3).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(EvalResultStatus.EXEC_SUCCEEDED.name());
            assertThat(r.attemptCount()).isEqualTo(3);
            List<EvalAttemptView> attempts = evalRunService.listAttempts(run3.id(), r.id());
            assertThat(attempts).hasSize(3);
            assertThat(attempts.get(0).status()).isIn(EvalAttemptStatus.FAILED.name(), EvalAttemptStatus.TIMEOUT.name());
            assertThat(attempts.get(1).status()).isIn(EvalAttemptStatus.FAILED.name(), EvalAttemptStatus.TIMEOUT.name());
            assertThat(attempts.get(2).status()).isEqualTo(EvalAttemptStatus.SUCCEEDED.name());
        });

        // maxAttempts=2: all cases exhaust retries -> Run ends FAILED
        EvalRunDetailView run2 = evalRunService.createAndStart(new CreateEvalRunRequest(
                "flaky-exhaust-2",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                null,
                "{{question}}",
                4,
                2,
                10_000L,
                1000.0,
                1000,
                null,
                null));

        EvalRunDetailView finished2 = awaitTerminal(run2.id(), Duration.ofSeconds(10));
        assertThat(finished2.status()).isEqualTo(EvalRunStatus.FAILED.name());
        assertThat(finished2.failedCases()).isEqualTo(6);
    }

    @Test
    void budgetCircuitBreakerTripsMidRunAndSkipsRemainingPendingCases() throws Exception {
        DatasetVersionEntity version = createDataset("ds-budget", 30);
        // Very high unit price so 1-2 cases exceed maxCostUsd=0.05
        ProviderView provider = createMockProvider(
                "mock-expensive",
                "mock-good",
                "{\"profile\":\"mock-good\",\"seed\":1,\"latencyMs\":10}",
                "{\"inputPer1k\":10.0,\"outputPer1k\":10.0}");

        EvalRunDetailView run = evalRunService.createAndStart(new CreateEvalRunRequest(
                "budget-circuit-breaker",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                null,
                "{{question}}",
                2,
                3,
                10_000L,
                1000.0,
                1000,
                new BigDecimal("0.05"),
                null));

        EvalRunDetailView finished = awaitTerminal(run.id(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.PARTIAL_FAILED.name());
        assertThat(finished.budgetExceeded()).isTrue();
        assertThat(finished.budgetExceededReason()).isEqualTo(RunBudgetTracker.REASON_COST);
        assertThat(finished.succeededCases()).isGreaterThan(0);
        assertThat(finished.skippedCases()).isGreaterThan(0);
        assertThat(finished.succeededCases() + finished.skippedCases() + finished.failedCases()).isEqualTo(30);

        List<EvalResultView> skipped = evalRunService.listResults(run.id(), EvalResultStatus.SKIPPED, null);
        assertThat(skipped).isNotEmpty();
        assertThat(skipped).allSatisfy(r -> assertThat(r.errorType()).isEqualTo("BUDGET_EXCEEDED"));
    }

    @Test
    void cancelMidRunSkipsRemainingAndFinalizesAsCancelled() throws Exception {
        DatasetVersionEntity version = createDataset("ds-cancel", 20);
        ProviderView provider = createMockProvider(
                "mock-slow-cancel",
                "mock-slow",
                "{\"profile\":\"mock-slow\",\"seed\":1,\"latencyMs\":120}",
                null);

        EvalRunDetailView run = evalRunService.createAndStart(new CreateEvalRunRequest(
                "cancel-test",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                null,
                "{{question}}",
                2,
                3,
                10_000L,
                1000.0,
                1000,
                null,
                null));

        Thread.sleep(100);
        evalRunService.cancelRun(run.id());

        EvalRunDetailView finished = awaitTerminal(run.id(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.CANCELLED.name());
        assertThat(finished.cancelRequested()).isTrue();
        assertThat(finished.skippedCases()).isGreaterThan(0);
    }

    @Test
    void idempotentExecutionNeverReschedulesAlreadySucceededCases() throws Exception {
        DatasetVersionEntity version = createDataset("ds-idem", 5);
        ProviderView provider = createMockProvider(
                "mock-idem",
                "mock-good",
                "{\"profile\":\"mock-good\",\"seed\":5,\"latencyMs\":0}",
                null);

        EvalRunDetailView created = evalRunService.createAndStart(new CreateEvalRunRequest(
                "idem-test",
                version.getDatasetId(),
                version.getVersionNo(),
                TargetType.PROMPT,
                provider.id(),
                null,
                null,
                "{{question}}",
                2,
                3,
                10_000L,
                1000.0,
                1000,
                null,
                null));

        EvalRunDetailView finished = awaitTerminal(created.id(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.COMPLETED.name());

        // Reset only 2 cases to PENDING while 3 stay EXEC_SUCCEEDED, then re-submit run
        List<EvalResultView> results = evalRunService.listResults(created.id(), null, null);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 2; i++) {
            EvalResultEntity entity = evalResultMapper.selectById(results.get(i).id());
            entity.setStatus(EvalResultStatus.PENDING.name());
            entity.setUpdatedAt(now);
            evalResultMapper.updateById(entity);
        }

        evalRunExecutor.submitRun(created.id()).join();

        List<EvalResultView> after = evalRunService.listResults(created.id(), null, null);
        // First 2 cases were re-executed (attemptCount=2), last 3 were NEVER touched (attemptCount stays 1)
        assertThat(after.get(0).attemptCount()).isEqualTo(2);
        assertThat(after.get(1).attemptCount()).isEqualTo(2);
        assertThat(after.get(2).attemptCount()).isEqualTo(1);
        assertThat(after.get(3).attemptCount()).isEqualTo(1);
        assertThat(after.get(4).attemptCount()).isEqualTo(1);
    }
}
