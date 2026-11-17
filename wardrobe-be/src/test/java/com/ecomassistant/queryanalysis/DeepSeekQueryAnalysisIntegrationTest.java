package com.ecomassistant.queryanalysis;

import com.ecomassistant.retrieval.QdrantRecommendDocumentRetriever;
import com.ecomassistant.support.AbstractQueryAnalysisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeepSeekQueryAnalysisIntegrationTest extends AbstractQueryAnalysisIntegrationTest {
    private static final String CHAT_PATH = "/chat/completions";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ANALYSIS = """
            {"allowed":true,"rejectionReasons":[],"suggestedPrompts":[],"searchQuery":"rain jacket",
             "negativeExamples":[],"priceConstraint":{"operator":"LESS_THAN","value":%d},"sortByLowestPrice":false}
            """;

    @MockitoBean
    private QdrantRecommendDocumentRetriever retriever;

    @DynamicPropertySource
    static void configureDeepSeek(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "deepseek");
        registry.add("spring.ai.deepseek.base-url", wireMock::baseUrl);
        registry.add("spring.ai.deepseek.api-key", () -> "deepseek-test-key");
        registry.add("spring.ai.retry.max-attempts", () -> "1");
        registry.add("spring.ai.retry.backoff.initial-interval", () -> "1ms");
    }

    @Test
    void usesDeepSeekJsonModeAndPreservesConversation() throws Exception {
        when(retriever.retrieve(any())).thenReturn(List.of());
        wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
                .atPriority(2)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(completion("stop", ANALYSIS.formatted(120)))));
        wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
                .withRequestBody(containing("under 80 instead"))
                .atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(completion("stop", ANALYSIS.formatted(80)))));

        mockMvc.perform(get("/api/v1/search").param("message", "rain jacket under 120")
                        .param("conversationId", "deepseek-budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretation.priceConstraint.value").value(120));
        mockMvc.perform(get("/api/v1/search").param("message", "under 80 instead")
                        .param("conversationId", "deepseek-budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretation.priceConstraint.value").value(80));

        assertThat(wireMock.getAllServeEvents()).hasSize(2);
        var request = wireMock.getAllServeEvents().stream()
                .map(event -> event.getRequest())
                .filter(candidate -> candidate.getBodyAsString().contains("under 80 instead"))
                .findFirst().orElseThrow();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer deepseek-test-key");
        var body = JSON.readTree(request.getBodyAsString());
        assertThat(body.path("model").asString()).isEqualTo("deepseek-flash");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(500);
        assertThat(body.at("/thinking/type").asString()).isEqualTo("disabled");
        assertThat(body.at("/response_format/type").asString()).isEqualTo("json_object");
        assertThat(body.at("/messages/0/content").asString()).contains("Return a JSON object", "\"priceConstraint\"");
        assertThat(body.at("/messages/1/content").asString()).isEqualTo("rain jacket under 120");
        assertThat(body.at("/messages/2/role").asString()).isEqualTo("assistant");
        assertThat(body.at("/messages/3/content").asString()).isEqualTo("under 80 instead");
    }

    @ParameterizedTest
    @CsvSource({"401,1", "503,2"})
    void mapsProviderFailureAndAppliesRetryBound(int statusCode, int expectedRequests) throws Exception {
        wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(statusCode).withBody("fixture error")));
        expectAnalysisFailure();
        assertThat(wireMock.getAllServeEvents()).hasSize(expectedRequests);
    }

    @Test
    void rejectsTruncatedAnalysisBeforeRetrieval() throws Exception {
        wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(completion("length", ANALYSIS.formatted(120)))));
        expectAnalysisFailure();
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    @Test
    void rejectsSchemaInvalidJsonAfterOneSchemaRetry() throws Exception {
        wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(completion("stop", "{\"allowed\":\"not a boolean\"}"))));
        expectAnalysisFailure();
        assertThat(wireMock.getAllServeEvents()).hasSize(2);
    }

    private void expectAnalysisFailure() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("message", "rain jacket")
                        .param("conversationId", "deepseek-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Illegal assistant response"));
        verifyNoInteractions(retriever);
    }

    private static String completion(String finishReason, String content) {
        return JSON.writeValueAsString(Map.of(
                "id", "deepseek-test",
                "object", "chat.completion",
                "created", 1,
                "model", "deepseek-flash",
                "choices", List.of(Map.of("index", 0, "finish_reason", finishReason,
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)));
    }
}
