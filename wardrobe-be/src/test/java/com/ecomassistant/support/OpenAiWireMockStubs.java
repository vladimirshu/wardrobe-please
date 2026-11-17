package com.ecomassistant.support;

import com.ecomassistant.guardrails.QueryAnalysis;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WireMock fixtures for structured query analysis through the OpenAI Chat Completions API. */
public final class OpenAiWireMockStubs {

    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** Marker present in the query-analysis system prompt. */
    public static final String QUERY_ANALYSIS_MARKER = "query-analysis step";
    public static final String PRODUCT_FILTER_MARKER = "product-filtering step";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private OpenAiWireMockStubs() {
    }

    public static void stubResponse(WireMockServer server, QueryAnalysis analysis) {
        stubResponseContent(server, queryAnalysisJson(analysis));
    }

    /** Stubs a single response whose message content is the given raw JSON string. */
    public static void stubResponseContent(WireMockServer server, String content) {
        server.stubFor(WireMock.post(WireMock.urlEqualTo(CHAT_COMPLETIONS_PATH))
            .willReturn(WireMock.aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(completedResponse(content))));
    }

    /**
     * Stubs a single response matched on the request body containing the given marker,
     * e.g. the {@link #QUERY_ANALYSIS_MARKER} carried by the query-analysis prompt.
     */
    public static void stubResponseForMarker(WireMockServer server, String marker, String content) {
        server.stubFor(WireMock.post(WireMock.urlEqualTo(CHAT_COMPLETIONS_PATH))
            .withRequestBody(WireMock.containing(marker))
            .willReturn(WireMock.aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(completedResponse(content))));
    }

    /** Stubs the query-analysis call to return the given {@link QueryAnalysis}. */
    public static void stubQueryAnalysis(WireMockServer server, QueryAnalysis analysis) {
        stubResponseForMarker(server, QUERY_ANALYSIS_MARKER, queryAnalysisJson(analysis));
    }

    public static void stubProductSelection(WireMockServer server, String... skus) {
        stubResponseForMarker(server, PRODUCT_FILTER_MARKER, write(Map.of("skus", List.of(skus))));
    }

    /**
     * Stubs a sequence of responses for the given marker, one per successive request
     * containing that marker, using a scenario. Useful for multi-turn conversations where
     * successive query-analysis calls must return different results.
     */
    public static void stubResponseForMarkerSequence(WireMockServer server, String marker, String... contents) {
        String scenario = "marker-sequence-" + marker;
        for (int i = 0; i < contents.length; i++) {
            String currentState = i == 0 ? Scenario.STARTED : "step-" + i;
            String nextState = i == contents.length - 1 ? Scenario.STARTED : "step-" + (i + 1);
            server.stubFor(WireMock.post(WireMock.urlEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(WireMock.containing(marker))
                .inScenario(scenario)
                .whenScenarioStateIs(currentState)
                .willReturn(WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(completedResponse(contents[i])))
                .willSetStateTo(nextState));
        }
    }

    /** Stubs a sequence of responses, one per successive request, using a scenario. */
    public static void stubResponseSequence(WireMockServer server, String... contents) {
        String scenario = "chat-completions-sequence";
        for (int i = 0; i < contents.length; i++) {
            String currentState = i == 0 ? Scenario.STARTED : "step-" + i;
            String nextState = i == contents.length - 1 ? Scenario.STARTED : "step-" + (i + 1);
            server.stubFor(WireMock.post(WireMock.urlEqualTo(CHAT_COMPLETIONS_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs(currentState)
                .willReturn(WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(completedResponse(contents[i])))
                .willSetStateTo(nextState));
        }
    }

    /** Serializes the strict output shape, including an explicit null price constraint. */
    public static String queryAnalysisJson(QueryAnalysis analysis) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("allowed", analysis.allowed());
        map.put("rejectionReasons", analysis.rejectionReasons());
        map.put("suggestedPrompts", analysis.suggestedPrompts());
        map.put("searchQuery", analysis.searchQuery());
        map.put("negativeExamples", analysis.negativeExamples());
        if (analysis.priceConstraint() != null) {
            map.put("priceConstraint", Map.of(
                    "operator", analysis.priceConstraint().operator().name(),
                    "value", analysis.priceConstraint().value()));
        } else {
            map.put("priceConstraint", null);
        }
        map.put("sortByLowestPrice", analysis.sortByLowestPrice());
        return write(map);
    }

    public static String completedResponse(String content) {
        return write(Map.of(
                "id", "chatcmpl-test",
                "object", "chat.completion",
                "created", 1,
                "model", "gpt-6-luna",
                "choices", List.of(Map.of(
                        "index", 0,
                        "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30,
                        "completion_tokens_details", Map.of("reasoning_tokens", 5))));
    }

    private static String write(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value, e);
        }
    }
}
