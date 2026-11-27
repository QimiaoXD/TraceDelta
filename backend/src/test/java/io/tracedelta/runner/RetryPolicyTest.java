package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;

import io.tracedelta.provider.llm.LlmErrorType;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void classifiesRetryableAndNonRetryableErrorsAccurately() {
        RetryPolicy policy = new RetryPolicy(maxJitter -> 0L);

        assertThat(policy.isRetryable(LlmErrorType.RATE_LIMITED, "429")).isTrue();
        assertThat(policy.isRetryable(LlmErrorType.TIMEOUT, "Upstream request timed out")).isTrue();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream HTTP status 503")).isTrue();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream connection error")).isTrue();

        assertThat(policy.isRetryable(LlmErrorType.NONE, null)).isFalse();
        assertThat(policy.isRetryable(LlmErrorType.MALFORMED_RESPONSE, "Bad JSON")).isFalse();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream HTTP status 400")).isFalse();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream HTTP status 401")).isFalse();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream HTTP status 403")).isFalse();
        assertThat(policy.isRetryable(LlmErrorType.HTTP_ERROR, "Upstream HTTP status 404")).isFalse();
    }

    @Test
    void exponentialBackoffRespectsCapAndJitter() {
        RetryPolicy zeroJitter = new RetryPolicy(maxJitter -> 0L);
        assertThat(zeroJitter.computeDelayMs(1, 200L, 1000L)).isEqualTo(200L);
        assertThat(zeroJitter.computeDelayMs(2, 200L, 1000L)).isEqualTo(400L);
        assertThat(zeroJitter.computeDelayMs(3, 200L, 1000L)).isEqualTo(800L);
        assertThat(zeroJitter.computeDelayMs(4, 200L, 1000L)).isEqualTo(1000L);

        RetryPolicy maxJitter = new RetryPolicy(bound -> bound);
        assertThat(maxJitter.computeDelayMs(1, 200L, 1000L)).isEqualTo(300L);
    }

    @Test
    void sleepBackoffReturnsFalseImmediatelyWhenCancelled() {
        RetryPolicy policy = new RetryPolicy(maxJitter -> 0L);
        long start = System.nanoTime();
        boolean completed = policy.sleepBackoff(5_000L, () -> true);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(completed).isFalse();
        assertThat(elapsedMs).isLessThan(500L);
    }
}
