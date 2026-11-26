package io.tracedelta.dataset;

import com.fasterxml.jackson.databind.JsonNode;

public record CaseAssertion(String type, JsonNode expected, JsonNode params) {

    public CaseAssertion {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("assertion type must not be blank");
        }
    }
}
