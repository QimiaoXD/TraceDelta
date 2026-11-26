package io.tracedelta.provider.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.ProviderConfigEntity;
import io.tracedelta.provider.ProviderType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OpenAiCompatibleLlmClientTest {

    private MockWebServer server;
    private OpenAiCompatibleLlmClient client;
    private ProviderConfigEntity provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleLlmClient(new ObjectMapper());
        provider = new ProviderConfigEntity();
        provider.setId(1L);
        provider.setName("test-openai");
        provider.setProviderType(ProviderType.OPENAI_COMPATIBLE.name());
        provider.setBaseUrl(server.url("/v1").toString());
        provider.setDefaultModel("gpt-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private LlmRequest request(Duration timeout) {
        return new LlmRequest(
                "gpt-test",
                List.of(new LlmMessage("user", "Say hi")),
                "case-1",
                1,
                Map.of(),
                timeout);
    }

    @Test
    void successParsesContentAndUsage() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [{"message": {"role": "assistant", "content": "Hello back!"}}],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 5}
                        }
                        """));

        LlmResponse res = client.chat(provider, "test-key", request(Duration.ofSeconds(5)));
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();

        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"gpt-test\"");

        assertThat(res.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(res.content()).isEqualTo("Hello back!");
        assertThat(res.inputTokens()).isEqualTo(12);
        assertThat(res.outputTokens()).isEqualTo(5);
        assertThat(res.ttftMs()).isNull();
    }

    @Test
    void http429MapsToRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limit\"}"));
        LlmResponse res = client.chat(provider, "test-key", request(Duration.ofSeconds(5)));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.RATE_LIMITED);
    }

    @Test
    void http500MapsToHttpError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server down"));
        LlmResponse res = client.chat(provider, "test-key", request(Duration.ofSeconds(5)));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.HTTP_ERROR);
    }

    @Test
    void readTimeoutMapsToTimeout() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"late\"}}]}"));
        LlmResponse res = client.chat(provider, "test-key", request(Duration.ofMillis(120)));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.TIMEOUT);
    }

    @Test
    void missingChoicesMapsToMalformedResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"abc\"}"));
        LlmResponse res = client.chat(provider, "test-key", request(Duration.ofSeconds(5)));
        assertThat(res.errorType()).isEqualTo(LlmErrorType.MALFORMED_RESPONSE);
    }

    @Test
    void errorMessageNeverContainsApiKey() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("invalid key test-key"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("error for test-key"));
            server.enqueue(new MockResponse().setResponseCode(200).setBodyDelay(400, TimeUnit.MILLISECONDS).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"bad\":\"test-key\"}"));

            List<LlmResponse> responses = List.of(
                    client.chat(provider, "test-key", request(Duration.ofSeconds(5))),
                    client.chat(provider, "test-key", request(Duration.ofSeconds(5))),
                    client.chat(provider, "test-key", request(Duration.ofMillis(100))),
                    client.chat(provider, "test-key", request(Duration.ofSeconds(5))));

            for (LlmResponse r : responses) {
                assertThat(r.errorType()).isNotEqualTo(LlmErrorType.NONE);
                assertThat(r.errorMessage()).doesNotContain("test-key");
            }
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("test-key");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
