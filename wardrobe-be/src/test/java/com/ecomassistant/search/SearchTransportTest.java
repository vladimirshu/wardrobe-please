package com.ecomassistant.search;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ecomassistant.support.OpenAiWireMockStubs;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import java.time.Duration;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class SearchTransportTest {
    private WireMockServer server;
    @BeforeEach void start() { server = new WireMockServer(0); server.start(); }
    @AfterEach void stop() { server.stop(); }

    private SearchChatModel model() {
        return new SearchChatModel(OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().apiKey("local-test").baseUrl(server.baseUrl() + "/v1")
                        .model("local-test").maxRetries(0).timeout(Duration.ofSeconds(15)).build())
                .httpClientBuilderCustomizer(new SearchHttpTransport().searchOpenAiTransport()).build());
    }

    @Test void sdkRetriesCannotMultiplyTheSharedAllowance() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503).withBody("PRIVATE PROVIDER PAYLOAD")));
        try (var budget = new SearchBudget()) {
            assertThatThrownBy(() -> model().call(new Prompt("private query")))
                    .isInstanceOf(TransientDependencyException.class)
                    .hasMessage("Dependency temporarily unavailable");
            server.verify(4, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        }
    }

    @Test void schemaCorrectionSharesAllowanceWithTransportAndKeepsOneRepeatLimit() {
        OpenAiWireMockStubs.stubResponseSequence(server, "bad", "{\"skus\":[]}");
        var client = ChatClient.builder(model()).defaultAdvisors(
                new BudgetSchemaAdvisor("{\"type\":\"object\",\"required\":[\"skus\"]}")).build();
        try (var budget = new SearchBudget()) {
            budget.tryAdditionalAttempt();
            budget.tryAdditionalAttempt();
            assertThat(client.prompt("query").call().content()).isEqualTo("{\"skus\":[]}");
            assertThat(budget.tryAdditionalAttempt()).isFalse();
            server.verify(2, postRequestedFor(anyUrl()));
        }
        server.resetAll();
        OpenAiWireMockStubs.stubResponseContent(server, "invalid");
        try (var budget = new SearchBudget()) {
            client.prompt("query").call().content();
            server.verify(2, postRequestedFor(anyUrl()));
        }
    }

    @Test void noSchemaSendAfterSharedExhaustion() {
        OpenAiWireMockStubs.stubResponseContent(server, "invalid");
        var client = ChatClient.builder(model()).defaultAdvisors(
                new BudgetSchemaAdvisor("{\"type\":\"object\"}")).build();
        try (var budget = new SearchBudget()) {
            for (int i = 0; i < 3; i++) budget.tryAdditionalAttempt();
            client.prompt("query").call().content();
            server.verify(1, postRequestedFor(anyUrl()));
        }
    }

    @Test void remainingDeadlineCancelsChatBeforeAnotherStageCanStart() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withFixedDelay(3000)));
        var model = model();
        long start = System.nanoTime();
        try (var budget = new SearchBudget(Duration.ofMillis(250), System::nanoTime)) {
            assertThatThrownBy(() -> model.call(new Prompt("query"))).isInstanceOf(SearchTimeoutException.class);
            assertThatThrownBy(budget::check).isInstanceOf(SearchTimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        server.verify(1, postRequestedFor(anyUrl()));
    }

    @Test void embeddingsUseSameCancellationAndRetryAllowance() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
        var raw = OpenAiEmbeddingModel.builder().options(OpenAiEmbeddingOptions.builder()
                .apiKey("local-test").baseUrl(server.baseUrl() + "/v1").model("local-embedding").maxRetries(0).build())
                .httpClientBuilderCustomizer(new SearchHttpTransport().searchOpenAiTransport()).build();
        var embedding = new SearchEmbeddingModel(raw);
        try (var budget = new SearchBudget()) {
            budget.tryAdditionalAttempt(); // earlier analysis schema repeat
            assertThatThrownBy(() -> embedding.embed("query")).isInstanceOf(TransientDependencyException.class);
            server.verify(3, postRequestedFor(urlEqualTo("/v1/embeddings")));
        }
    }

    @Test void retryAfterCannotHoldSearchPastItsDeadline() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));
        var model = model();
        try (var budget = new SearchBudget()) {
            assertThatThrownBy(() -> model.call(new Prompt("query"))).isInstanceOf(SearchTimeoutException.class);
            server.verify(1, postRequestedFor(anyUrl()));
        }
    }

    @Test void providerAttemptStopsAtFiveSecondsEvenWithMoreSearchTime() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withFixedDelay(15000)));
        var model = model();
        long start = System.nanoTime();
        try (var budget = new SearchBudget()) {
            for (int i = 0; i < 3; i++) budget.tryAdditionalAttempt();
            assertThatThrownBy(() -> model.call(new Prompt("query"))).isInstanceOf(SearchTimeoutException.class);
            assertThat(budget.remainingNanos()).isPositive();
        }
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isBetween(Duration.ofMillis(4700), Duration.ofMillis(5700));
        server.verify(1, postRequestedFor(anyUrl()));
    }

    @Test void delayedResponseBodyIsCancelledNotJustDelayedHeaders() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}").withChunkedDribbleDelay(10, 3000)));
        var raw = OpenAiEmbeddingModel.builder().options(OpenAiEmbeddingOptions.builder()
                .apiKey("local-test").baseUrl(server.baseUrl() + "/v1").maxRetries(0).build())
                .httpClientBuilderCustomizer(new SearchHttpTransport().searchOpenAiTransport()).build();
        long start = System.nanoTime();
        try (var budget = new SearchBudget(Duration.ofMillis(400), System::nanoTime)) {
            assertThatThrownBy(() -> new SearchEmbeddingModel(raw).embed("query"))
                    .isInstanceOf(SearchTimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        server.verify(1, postRequestedFor(anyUrl()));
    }

    @Test void deepSeekHttpExchangeAlsoCancelsDuringTheBody() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withBody("slow body").withChunkedDribbleDelay(10, 3000)));
        var factory = new SearchRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        var rest = org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
        try (var budget = new SearchBudget(Duration.ofMillis(400), System::nanoTime)) {
            assertThatThrownBy(() -> budget.call(() -> rest.post().uri(server.baseUrl())
                    .body("query").retrieve().body(String.class), true)).isInstanceOf(SearchTimeoutException.class);
        }
        server.verify(1, postRequestedFor(anyUrl()));
    }
}
