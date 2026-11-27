package io.tracedelta.target;

import java.time.Duration;

public record TargetExecutionContext(
        TargetConfigSnapshot targetConfig,
        CaseSnapshot caseSnapshot,
        int attemptNo,
        Duration timeout) {
}
