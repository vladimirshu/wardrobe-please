package com.ecomassistant.security;

import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.retrieval.QdrantRecommendDocumentRetriever;
import com.ecomassistant.support.AbstractQueryAnalysisIntegrationTest;
import com.ecomassistant.support.OpenAiWireMockStubs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.UUID;
import static com.ecomassistant.support.SearchRequests.jsonSearch;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.TestPropertySource(properties = {"spring.ai.vectorstore.qdrant.use-tls=false"})
@ActiveProfiles("aws")
@ExtendWith(OutputCaptureExtension.class)
class AwsLoggingIntegrationTest extends AbstractQueryAnalysisIntegrationTest {
    private static final String PROMPT = "private-prompt-marker jacket";
    private static final String PAYLOAD = "private-provider-payload-marker";
    @MockitoBean QdrantRecommendDocumentRetriever retriever;

    @Test
    void successfulSearchOmitsPrompt(CapturedOutput output) throws Exception {
        OpenAiWireMockStubs.stubResponse(wireMock,
                new QueryAnalysis(true, List.of(), List.of(), "jacket", List.of(), null, false));
        when(retriever.retrieve(any())).thenReturn(List.of());
        mockMvc.perform(jsonSearch("message", PROMPT, "conversationId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertPrivate(output);
        assertThat(output.getAll()).contains("LLM query analysis completed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "schema", "malformed", "auth", "unavailable"})
    void failuresOmitPromptAndProviderBody(String failure, CapturedOutput output) throws Exception {
        int code = switch (failure) { case "auth" -> 401; case "unavailable" -> 503; default -> 200; };
        String body = switch (failure) {
            case "empty" -> OpenAiWireMockStubs.completedResponse("");
            case "schema" -> OpenAiWireMockStubs.completedResponse("{\"allowed\":\"" + PAYLOAD + "\"}");
            case "malformed" -> "{\"" + PAYLOAD;
            default -> "{\"error\":{\"message\":\"" + PAYLOAD + "\",\"type\":\"provider_error\"}}";
        };
        wireMock.stubFor(post(urlEqualTo(OpenAiWireMockStubs.CHAT_COMPLETIONS_PATH))
                .willReturn(aResponse().withStatus(code).withHeader("Content-Type", "application/json").withBody(body)));
        mockMvc.perform(jsonSearch("message", PROMPT, "conversationId", UUID.randomUUID().toString()))
                .andExpect(status().isInternalServerError());
        assertPrivate(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "empty", "schema", "malformed", "auth", "unavailable", "unknown-sku"})
    void refinementOmitsPromptsAndProviderPayloads(String failure, CapturedOutput output) throws Exception {
        OpenAiWireMockStubs.stubQueryAnalysis(wireMock,
                new QueryAnalysis(true, List.of(), List.of(), "jacket", List.of(), null, false));
        when(retriever.retrieve(any())).thenReturn(List.of(
                new org.springframework.ai.document.Document("jacket", java.util.Map.of("sku", "RAIN1"))));
        int code = switch (failure) { case "auth" -> 401; case "unavailable" -> 503; default -> 200; };
        String body = switch (failure) {
            case "success" -> OpenAiWireMockStubs.completedResponse("{\"skus\":[]}");
            case "unknown-sku" -> OpenAiWireMockStubs.completedResponse("{\"skus\":[\"" + PAYLOAD + "\"]}");
            case "empty" -> OpenAiWireMockStubs.completedResponse("");
            case "schema" -> OpenAiWireMockStubs.completedResponse("{\"skus\":\"" + PAYLOAD + "\"}");
            case "malformed" -> "{\"" + PAYLOAD;
            default -> "{\"error\":{\"message\":\"" + PAYLOAD + "\",\"type\":\"provider_error\"}}";
        };
        wireMock.stubFor(post(urlEqualTo(OpenAiWireMockStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(OpenAiWireMockStubs.PRODUCT_FILTER_MARKER))
                .willReturn(aResponse().withStatus(code).withHeader("Content-Type", "application/json").withBody(body)));
        var request = mockMvc.perform(jsonSearch("message", PROMPT, "conversationId", UUID.randomUUID().toString()));
        if (failure.equals("success") || failure.equals("unknown-sku")) request.andExpect(status().isOk());
        else request.andExpect(status().isInternalServerError());
        assertPrivate(output);
    }

    @Test
    void retrievalProviderFailureOmitsRawException(CapturedOutput output) throws Exception {
        OpenAiWireMockStubs.stubQueryAnalysis(wireMock,
                new QueryAnalysis(true, List.of(), List.of(), "jacket", List.of(), null, false));
        when(retriever.retrieve(any())).thenThrow(
                new org.springframework.web.client.RestClientException(PROMPT + PAYLOAD));
        mockMvc.perform(jsonSearch("message", PROMPT, "conversationId", UUID.randomUUID().toString()))
                .andExpect(status().isInternalServerError());
        assertPrivate(output);
    }

    private static void assertPrivate(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(PROMPT, PAYLOAD, "Bearer test-key");
    }
}
