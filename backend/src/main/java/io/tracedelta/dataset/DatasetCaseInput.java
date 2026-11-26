package io.tracedelta.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record DatasetCaseInput(
        String caseKey,
        JsonNode input,
        JsonNode messages,
        String referenceAnswer,
        List<String> expectedDocIds,
        List<CaseAssertion> assertions,
        List<String> tags) {

    public DatasetCaseInput {
        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("case_key must not be blank");
        }
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("input must be a JSON object");
        }
        assertions = (assertions == null) ? List.of() : List.copyOf(assertions);
        expectedDocIds = (expectedDocIds == null) ? null : List.copyOf(expectedDocIds);
        tags = (tags == null) ? null : List.copyOf(tags);
    }
}
