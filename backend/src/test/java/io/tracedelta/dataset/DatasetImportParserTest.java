package io.tracedelta.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetImportParserTest {

    private final DatasetImportParser parser = new DatasetImportParser(new ObjectMapper());

    @Test
    void parsesJsonArray() {
        String json = """
                [
                  {
                    "case_key": "q-1",
                    "input": {"question": "What is Java 21?"},
                    "reference_answer": "An LTS release",
                    "expected_doc_ids": ["doc-a"],
                    "assertions": [{"type": "contains", "expected": "LTS"}],
                    "tags": ["java"]
                  }
                ]
                """;
        List<DatasetCaseInput> items = parser.parse("json", json);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).caseKey()).isEqualTo("q-1");
        assertThat(items.get(0).input().get("question").asText()).isEqualTo("What is Java 21?");
        assertThat(items.get(0).referenceAnswer()).isEqualTo("An LTS release");
        assertThat(items.get(0).expectedDocIds()).containsExactly("doc-a");
        assertThat(items.get(0).assertions()).hasSize(1);
        assertThat(items.get(0).tags()).containsExactly("java");
    }

    @Test
    void parsesJsonlSkippingBlankLines() {
        String jsonl = """
                {"case_key":"c1","input":{"question":"Q1"},"reference_answer":"A1"}

                {"case_key":"c2","input":{"question":"Q2"}}
                """;
        List<DatasetCaseInput> items = parser.parse("JSONL", jsonl);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).caseKey()).isEqualTo("c1");
        assertThat(items.get(1).caseKey()).isEqualTo("c2");
        assertThat(items.get(1).assertions()).isEmpty();
    }

    @Test
    void parsesTwoColumnCsvWithGeneratedKeys() {
        String csv = """
                question,reference_answer
                "Hello, world","Greeting, yes"
                Simple Q,Simple A
                """;
        List<DatasetCaseInput> items = parser.parse("CSV", csv);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).caseKey()).isEqualTo("case-0001");
        assertThat(items.get(0).input().get("question").asText()).isEqualTo("Hello, world");
        assertThat(items.get(0).referenceAnswer()).isEqualTo("Greeting, yes");
        assertThat(items.get(1).caseKey()).isEqualTo("case-0002");
        assertThat(items.get(1).input().get("question").asText()).isEqualTo("Simple Q");
    }

    @Test
    void jsonlBadLineReportsLineNumber() {
        String jsonl = """
                {"case_key":"c1","input":{"question":"Q1"}}
                {not-valid-json
                """;
        assertThatThrownBy(() -> parser.parse("JSONL", jsonl))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo(ErrorCode.IMPORT_INVALID);
                    assertThat(biz.getMessage()).contains("第 2 行");
                });
    }

    @Test
    void missingCaseKeyInJsonIsRejected() {
        String json = "[{\"input\":{\"question\":\"Q\"}}]";
        assertThatThrownBy(() -> parser.parse("JSON", json))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.IMPORT_INVALID);
    }

    @Test
    void inputNotObjectIsRejected() {
        String json = "[{\"case_key\":\"c1\",\"input\":\"plain-string\"}]";
        assertThatThrownBy(() -> parser.parse("JSON", json))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.IMPORT_INVALID);
    }

    @Test
    void csvWrongHeaderIsRejected() {
        String csv = "prompt,answer\nhi,hello\n";
        assertThatThrownBy(() -> parser.parse("CSV", csv))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.IMPORT_INVALID);
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> parser.parse("YAML", "a: 1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.IMPORT_INVALID);
    }
}
