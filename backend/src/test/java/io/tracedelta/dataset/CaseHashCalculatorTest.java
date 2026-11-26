package io.tracedelta.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseHashCalculatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CaseHashCalculator calculator = new CaseHashCalculator(objectMapper);

    private DatasetCaseInput baseCase() throws Exception {
        return new DatasetCaseInput(
                "case-1",
                objectMapper.readTree("{\"a\":1,\"b\":{\"y\":2,\"x\":3}}"),
                objectMapper.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]"),
                "ref-answer",
                List.of("doc-1", "doc-2"),
                List.of(new CaseAssertion("contains", objectMapper.readTree("\"ok\""), objectMapper.readTree("{\"k\":1,\"j\":2}"))),
                List.of("smoke"));
    }

    @Test
    void keyOrderDoesNotAffectHash() throws Exception {
        DatasetCaseInput c1 = new DatasetCaseInput(
                "k1",
                objectMapper.readTree("{\"a\":1,\"b\":{\"x\":3,\"y\":2}}"),
                null,
                "ans",
                List.of("d1"),
                List.of(new CaseAssertion("json_valid", null, objectMapper.readTree("{\"b\":2,\"a\":1}"))),
                List.of());
        DatasetCaseInput c2 = new DatasetCaseInput(
                "k1",
                objectMapper.readTree("{\"b\":{\"y\":2,\"x\":3},\"a\":1}"),
                null,
                "ans",
                List.of("d1"),
                List.of(new CaseAssertion("json_valid", null, objectMapper.readTree("{\"a\":1,\"b\":2}"))),
                List.of());

        assertThat(calculator.compute(c1)).isEqualTo(calculator.compute(c2));
    }

    @Test
    void changingReferenceAnswerChangesHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput changed = new DatasetCaseInput(
                base.caseKey(), base.input(), base.messages(), "other-answer",
                base.expectedDocIds(), base.assertions(), base.tags());
        assertThat(calculator.compute(changed)).isNotEqualTo(calculator.compute(base));
    }

    @Test
    void changingExpectedDocIdsChangesHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput changed = new DatasetCaseInput(
                base.caseKey(), base.input(), base.messages(), base.referenceAnswer(),
                List.of("doc-1", "doc-9"), base.assertions(), base.tags());
        assertThat(calculator.compute(changed)).isNotEqualTo(calculator.compute(base));
    }

    @Test
    void changingAssertionsChangesHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput changed = new DatasetCaseInput(
                base.caseKey(), base.input(), base.messages(), base.referenceAnswer(),
                base.expectedDocIds(),
                List.of(new CaseAssertion("exact_match", objectMapper.readTree("\"ok\""), null)),
                base.tags());
        assertThat(calculator.compute(changed)).isNotEqualTo(calculator.compute(base));
    }

    @Test
    void changingInputChangesHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput changed = new DatasetCaseInput(
                base.caseKey(), objectMapper.readTree("{\"a\":2,\"b\":{\"x\":3,\"y\":2}}"),
                base.messages(), base.referenceAnswer(), base.expectedDocIds(), base.assertions(), base.tags());
        assertThat(calculator.compute(changed)).isNotEqualTo(calculator.compute(base));
    }

    @Test
    void changingMessagesChangesHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput changed = new DatasetCaseInput(
                base.caseKey(), base.input(),
                objectMapper.readTree("[{\"role\":\"user\",\"content\":\"bye\"}]"),
                base.referenceAnswer(), base.expectedDocIds(), base.assertions(), base.tags());
        assertThat(calculator.compute(changed)).isNotEqualTo(calculator.compute(base));
    }

    @Test
    void tagsAndCaseKeyDoNotAffectHash() throws Exception {
        DatasetCaseInput base = baseCase();
        DatasetCaseInput renamed = new DatasetCaseInput(
                "case-999", base.input(), base.messages(), base.referenceAnswer(),
                base.expectedDocIds(), base.assertions(), List.of("other-tag"));
        assertThat(calculator.compute(renamed)).isEqualTo(calculator.compute(base));
    }

    @Test
    void hashIs64LowercaseHex() throws Exception {
        assertThat(calculator.compute(baseCase())).matches("^[0-9a-f]{64}$");
    }
}
