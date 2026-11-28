package io.tracedelta.trace;

public enum SpanKind {
    CASE_ROOT,
    ATTEMPT,
    PROMPT_RENDER,
    TEMPLATE_RENDER,
    LLM_CALL,
    HTTP_CALL,
    RETRIEVAL,
    EVALUATOR
}
