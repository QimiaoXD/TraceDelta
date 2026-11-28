package io.tracedelta.target.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPathExtractorTest {

    private final JsonPathExtractor extractor = new JsonPathExtractor(new ObjectMapper());

    @Test
    void extractsNestedFieldsArrayIndexesAndBracketQuotedKeys() {
        String json = """
                {
                  "data": {
                    "answer": "TraceDelta is ready",
                    "score": 98,
                    "ok": true
                  },
                  "choices": [
                    {"message": {"content": "first choice"}},
                    {"message": {"content": "second choice"}}
                  ],
                  "x-result": [
                    {"text": "bracket-zero"},
                    {"text": "bracket-one"}
                  ]
                }
                """;

        assertThat(extractor.extract(json, "$.data.answer")).contains("TraceDelta is ready");
        assertThat(extractor.extract(json, "$.data.score")).contains("98");
        assertThat(extractor.extract(json, "$.data.ok")).contains("true");
        assertThat(extractor.extract(json, "$.choices[0].message.content")).contains("first choice");
        assertThat(extractor.extract(json, "$.choices[1].message.content")).contains("second choice");
        assertThat(extractor.extract(json, "$['x-result'][1].text")).contains("bracket-one");
        assertThat(extractor.extract(json, "$")).isPresent();
    }

    @Test
    void returnsEmptyForMissingPathOutOfBoundsOrInvalidJson() {
        String json = "{\"data\":{\"items\":[\"a\"]}}";

        assertThat(extractor.extract(json, "$.data.missing")).isEmpty();
        assertThat(extractor.extract(json, "$.data.items[5]")).isEmpty();
        assertThat(extractor.extract("not-json", "$.data.answer")).isEmpty();
        assertThat(extractor.extract("", "$.data.answer")).isEmpty();
        assertThat(extractor.extract(json, "invalid.path")).isEmpty();
    }
}
