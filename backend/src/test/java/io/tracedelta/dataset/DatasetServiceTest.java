package io.tracedelta.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DatasetServiceTest {

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private CaseHashCalculator caseHashCalculator;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DatasetCaseInput makeCase(String key, String question, String refAnswer) throws Exception {
        return new DatasetCaseInput(
                key,
                objectMapper.readTree("{\"question\":\"" + question + "\"}"),
                null,
                refAnswer,
                null,
                List.of(),
                List.of("v"));
    }

    @Test
    void firstImportCreatesVersion1WithCaseCount() throws Exception {
        DatasetEntity ds = datasetService.create("ds-v1", "desc");
        DatasetVersionEntity v1 = datasetService.importVersion(
                ds.getId(), List.of(makeCase("c1", "Q1", "A1"), makeCase("c2", "Q2", "A2")), "init");

        assertThat(v1.getVersionNo()).isEqualTo(1);
        assertThat(v1.getCaseCount()).isEqualTo(2);
        assertThat(datasetService.listCases(ds.getId(), 1)).hasSize(2);
    }

    @Test
    void secondImportCreatesVersion2AndVersion1StaysUnchanged() throws Exception {
        DatasetEntity ds = datasetService.create("ds-immutable", "desc");
        DatasetCaseInput c1 = makeCase("c1", "Q1", "A1");
        datasetService.importVersion(ds.getId(), List.of(c1), "v1");
        String v1HashBefore = datasetService.listCases(ds.getId(), 1).get(0).getCaseHash();

        DatasetVersionEntity v2 = datasetService.importVersion(
                ds.getId(), List.of(makeCase("c1", "Q1-edited", "A1-new"), makeCase("c2", "Q2", "A2")), "v2");

        assertThat(v2.getVersionNo()).isEqualTo(2);
        assertThat(datasetService.listVersions(ds.getId()))
                .extracting(DatasetVersionEntity::getVersionNo)
                .containsExactly(1, 2);

        List<DatasetCaseEntity> v1CasesAfter = datasetService.listCases(ds.getId(), 1);
        assertThat(v1CasesAfter).hasSize(1);
        assertThat(v1CasesAfter.get(0).getCaseHash()).isEqualTo(v1HashBefore);
        assertThat(v1CasesAfter.get(0).getReferenceAnswer()).isEqualTo("A1");
    }

    @Test
    void duplicateCaseKeyRollsBackWholeBatch() throws Exception {
        DatasetEntity ds = datasetService.create("ds-dup-rollback", "desc");
        Integer casesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dataset_case", Integer.class);

        List<DatasetCaseInput> dupBatch = List.of(
                makeCase("dup-key-99", "Q1", "A1"),
                makeCase("dup-key-99", "Q2", "A2"));

        assertThatThrownBy(() -> datasetService.importVersion(ds.getId(), dupBatch, "bad"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo(ErrorCode.DUPLICATE_CASE_KEY);
                    assertThat(biz.getMessage()).contains("dup-key-99");
                });

        assertThat(datasetService.listVersions(ds.getId())).isEmpty();
        Integer casesAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dataset_case", Integer.class);
        assertThat(casesAfter).isEqualTo(casesBefore);
    }

    @Test
    void caseInsensitiveAndTrimDuplicateCaseKeyRollsBackWith400ErrorCode() throws Exception {
        DatasetEntity ds = datasetService.create("ds-dup-ci", "desc");
        List<DatasetCaseInput> dupBatch = List.of(
                makeCase("Case-Key-1", "Q1", "A1"),
                makeCase("case-key-1 ", "Q2", "A2"));

        assertThatThrownBy(() -> datasetService.importVersion(ds.getId(), dupBatch, "ci-dup"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CASE_KEY);

        assertThat(datasetService.listVersions(ds.getId())).isEmpty();
    }

    @Test
    void emptyBatchIsRejected() {
        DatasetEntity ds = datasetService.create("ds-empty", "desc");
        assertThatThrownBy(() -> datasetService.importVersion(ds.getId(), List.of(), "empty"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.IMPORT_INVALID);
    }

    @Test
    void unknownDatasetIsNotFound() throws Exception {
        List<DatasetCaseInput> oneCase = List.of(makeCase("c1", "Q", "A"));
        assertThatThrownBy(() -> datasetService.importVersion(999999L, oneCase, "nope"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void caseHashIsPersistedAndMatchesCalculator() throws Exception {
        DatasetEntity ds = datasetService.create("ds-hash", "desc");
        DatasetCaseInput input = makeCase("c-hash", "What is TraceDelta?", "Eval platform");
        datasetService.importVersion(ds.getId(), List.of(input), "v1");

        DatasetCaseEntity persisted = datasetService.listCases(ds.getId(), 1).get(0);
        assertThat(persisted.getCaseHash()).isEqualTo(caseHashCalculator.compute(input));
    }
}
