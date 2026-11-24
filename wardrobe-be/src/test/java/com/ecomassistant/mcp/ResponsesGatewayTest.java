package com.ecomassistant.mcp;

import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.search.SearchTimeoutException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class ResponsesGatewayTest {
    @Test void providerErrorsAndMalformedBodiesNeverReplayOrExposePayloads() {
        var server = new WireMockServer(0); server.start();
        try {
            var properties = new McpProperties(true, true, "https://catalog.test/api/v1/mcp",
                    server.baseUrl() + "/responses", 1200, Map.of());
            try (var gateway = new ClosingGateway(properties)) {
                for (var response : java.util.List.of(aResponse().withStatus(503).withBody("PRIVATE_PROVIDER_BODY"),
                        aResponse().withStatus(200).withBody("PRIVATE_PROVIDER_BODY"),
                        aResponse().withStatus(302).withHeader("Location", server.baseUrl() + "/redirect"))) {
                    server.resetAll(); server.stubFor(post("/responses").willReturn(response));
                    try (var budget = new SearchBudget()) {
                        assertThatThrownBy(() -> gateway.create(Map.of("input", "test"), budget))
                                .isInstanceOfAny(McpDependencyException.class, com.ecomassistant.exception.IllegalAssistantResponseException.class)
                                .hasMessageNotContaining("PRIVATE_PROVIDER_BODY");
                    }
                    server.verify(1, postRequestedFor(urlEqualTo("/responses")));
                    assertThat(server.getAllServeEvents()).hasSize(1);
                }
            }
        } finally { server.stop(); }
    }
    @Test void deadlineCancelsTheSingleHttpRequestAndCleansContext() {
        var server = new WireMockServer(0); server.start();
        try {
            server.stubFor(post("/responses").willReturn(aResponse().withFixedDelay(1500).withBody("{}")));
            var properties = new McpProperties(true, true, "https://catalog.test/api/v1/mcp", server.baseUrl() + "/responses", 1200, Map.of());
            try (var gateway = new ClosingGateway(properties); var budget = SearchBudget.bounded(Duration.ofMillis(500))) {
                assertThatThrownBy(() -> gateway.create(Map.of("input", "test"), budget)).isInstanceOf(SearchTimeoutException.class);
            }
            server.verify(1, postRequestedFor(urlEqualTo("/responses")));
            assertThat(SearchBudget.current()).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally { server.stop(); }
    }
    static class ClosingGateway extends ResponsesGateway implements AutoCloseable {
        ClosingGateway(McpProperties properties) { super(properties, "test-key"); }
        @Override public void close() { super.close(); }
    }
}
