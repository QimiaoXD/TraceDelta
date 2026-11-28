package io.tracedelta.target.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HttpRequestTemplateRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpRequestTemplateRenderer renderer = new HttpRequestTemplateRenderer(objectMapper);

    @Test
    void escapesQuotesBackslashesAndNewlinesInsideJsonStrings() throws Exception {
        String template = "{\"query\": \"{{question}}\", \"user\": \"{{meta.user}}\"}";
        String inputJson = "{\"question\": \"He said \\\"hi\\\"\\npath\\\\to\\\\file\", \"meta\": {\"user\": \"alice\"}}";

        String rendered = renderer.renderBody(template, inputJson);

        JsonNode parsed = objectMapper.readTree(rendered);
        assertThat(parsed.path("query").asText()).isEqualTo("He said \"hi\"\npath\\to\\file");
        assertThat(parsed.path("user").asText()).isEqualTo("alice");
    }

    @Test
    void substitutesMissingVariablesAsEmptyStringWithoutBreakingJson() throws Exception {
        String template = "{\"q\": \"{{question}}\", \"extra\": \"{{missing_field}}\"}";
        String rendered = renderer.renderBody(template, "{\"question\": \"hello\"}");

        JsonNode parsed = objectMapper.readTree(rendered);
        assertThat(parsed.path("q").asText()).isEqualTo("hello");
        assertThat(parsed.path("extra").asText()).isEmpty();
    }

    @Test
    void handlesBlankTemplateAndNonJsonTemplateCleanly() {
        assertThat(renderer.renderBody(null, "{\"question\":\"hi\"}")).isNull();
        assertThat(renderer.renderBody("   ", "{\"question\":\"hi\"}")).isNull();
        assertThat(renderer.renderBody("q={{question}}", "{\"question\":\"hello world\"}"))
                .isEqualTo("q=hello world");
    }
}
