package io.tracedelta.target;

public record CaseSnapshot(
        String caseKey,
        String caseHash,
        String inputJson,
        String messagesJson,
        String referenceAnswer) {
}
