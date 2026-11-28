package io.tracedelta.target;

public interface EvalTarget {

    TargetType type();

    String rateLimitKey(TargetConfigSnapshot config);

    TargetExecutionResult execute(TargetExecutionContext context);
}
