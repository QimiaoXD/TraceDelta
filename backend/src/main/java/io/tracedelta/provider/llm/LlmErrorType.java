package io.tracedelta.provider.llm;

public enum LlmErrorType {
    NONE,
    RATE_LIMITED,
    TIMEOUT,
    HTTP_ERROR,
    MALFORMED_RESPONSE
}
