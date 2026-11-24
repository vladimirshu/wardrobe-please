package com.ecomassistant.mcp;

import com.ecomassistant.VectorStoreUtils;
import com.ecomassistant.config.EcomSearchProperties;
import com.ecomassistant.dto.ProductDtoMapper;
import com.ecomassistant.dto.SearchInterpretationDto;
import com.ecomassistant.guardrails.*;
import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.service.SearchResult;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@Service
@ConditionalOnProperty(name = "ecom.mcp.storefront", havingValue = "true")
public class McpStorefrontSearch {
    private final McpProperties properties;
    private final ResponsesGateway gateway;
    private final SearchLeases leases;
    private final ChatMemory memory;
    private final QueryAnalysisValidator validator;
    private final ProductDtoMapper mapper;
    private final EcomSearchProperties searchProperties;
    private final String model;
    private final String instructions;
    public McpStorefrontSearch(McpProperties properties, ResponsesGateway gateway, SearchLeases leases,
            ChatMemory memory, QueryAnalysisValidator validator, ProductDtoMapper mapper,
            EcomSearchProperties searchProperties, @Value("${spring.ai.openai.chat.model}") String model,
            @Value("${spring.ai.model.chat}") String provider) throws java.io.IOException {
        if (!"openai".equals(provider)) throw new IllegalArgumentException("MCP storefront requires OpenAI chat");
        this.properties = properties; this.gateway = gateway; this.leases = leases; this.memory = memory;
        this.validator = validator; this.mapper = mapper; this.searchProperties = searchProperties; this.model = model;
        try (var input = new ClassPathResource("prompts/mcp-search.md").getInputStream()) {
            instructions = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public SearchResult search(String message, String conversationId) {
        if (SearchBudget.current() == null) {
            try (var budget = new SearchBudget()) { return execute(message, conversationId, budget); }
        }
        return execute(message, conversationId, SearchBudget.current());
    }
    private SearchResult execute(String message, String conversationId, SearchBudget budget) {
        try (var lease = leases.open(budget)) {
            var input = new ArrayList<Map<String, Object>>();
            for (var turn : memory.get(conversationId)) {
                if (turn instanceof UserMessage || turn instanceof AssistantMessage)
                    input.add(Map.of("role", turn instanceof UserMessage ? "user" : "assistant", "content", turn.getText()));
            }
            input.add(Map.of("role", "user", "content", message));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("model", model); payload.put("store", false); payload.put("background", false);
            payload.put("instructions", instructions); payload.put("input", input);
            payload.put("reasoning", Map.of("effort", "low"));
            payload.put("max_output_tokens", properties.maxOutputTokens());
            payload.put("parallel_tool_calls", false);
            payload.put("tools", List.of(Map.of("type", "mcp", "server_label", "wardrobe_catalog",
                    "server_url", properties.publicUrl(), "authorization", lease.token(),
                    "allowed_tools", List.of("search_catalog"), "require_approval", "never")));
            payload.put("text", Map.of("format", Map.of("type", "json_schema", "name", "catalog_selection",
                    "strict", true, "schema", outputSchema())));
            JsonNode response = gateway.create(payload, budget);
            JsonNode result = finalResult(response);
            QueryAnalysis analysis;
            try { analysis = CatalogMcpController.JSON.treeToValue(result.get("analysis"), QueryAnalysis.class); }
            catch (RuntimeException ex) { throw SearchLeases.invalid(); }
            var evidence = lease.evidence(analysis.allowed());
            checkTrace(response, evidence);
            try { validator.validate(analysis); }
            catch (GuardrailRejectedException ex) {
                if (!result.path("skus").isEmpty()) throw SearchLeases.invalid();
                remember(conversationId, message, ex.getQueryAnalysis());
                throw ex;
            }
            if (!CatalogSearch.from(analysis).equals(evidence.search())) throw SearchLeases.invalid();
            var selected = new HashSet<String>();
            for (var sku : result.path("skus")) if (!selected.add(sku.asString())) throw SearchLeases.invalid();
            if (selected.size() > searchProperties.maxResults()) throw SearchLeases.invalid();
            var known = new HashSet<String>();
            var products = new ArrayList<com.ecomassistant.dto.ProductDto>();
            for (var document : evidence.documents()) {
                var product = VectorStoreUtils.documentToProduct(document);
                if (!known.add(product.getSku())) throw SearchLeases.invalid();
                if (selected.contains(product.getSku())) {
                    ExactConstraints.verify(evidence.search(), document);
                    products.add(mapper.toDto(product));
                }
            }
            if (!known.containsAll(selected)) throw SearchLeases.invalid();
            budget.check();
            remember(conversationId, message, analysis);
            return new SearchResult(List.copyOf(products), SearchInterpretationDto.from(analysis));
        }
    }
    private void remember(String id, String message, QueryAnalysis analysis) {
        // Persist only the existing analysis summary, never provider traces, tokens, candidates or selections.
        memory.add(id, List.of(new UserMessage(message), new AssistantMessage(CatalogMcpController.JSON.writeValueAsString(analysis))));
    }
    static JsonNode outputSchema() {
        ObjectNode schema = CatalogMcpController.JSON.createObjectNode();
        schema.put("type", "object"); schema.put("additionalProperties", false);
        schema.putArray("required").add("analysis").add("skus");
        var props = schema.putObject("properties");
        props.set("analysis", CatalogMcpController.JSON.readTree(QueryAnalysis.strictJsonSchema()));
        props.putObject("skus").put("type", "array").putObject("items").put("type", "string");
        return schema;
    }
    private static JsonNode finalResult(JsonNode response) {
        if (response == null || !"completed".equals(response.path("status").asString())
                || response.hasNonNull("error") || !response.path("output").isArray()) throw SearchLeases.invalid();
        String text = null;
        for (var output : response.path("output")) {
            String type = output.path("type").asString();
            if (type.equals("message")) {
                if (!"assistant".equals(output.path("role").asString())
                        || !"completed".equals(output.path("status").asString())) throw SearchLeases.invalid();
                for (var content : output.path("content")) {
                    if (!"output_text".equals(content.path("type").asString())
                            || text != null || !content.path("text").isString()) throw SearchLeases.invalid();
                    text = content.path("text").asString();
                }
            } else if (!(type.equals("reasoning") || type.equals("mcp_call") || type.equals("mcp_list_tools"))) {
                throw SearchLeases.invalid();
            }
        }
        try {
            if (text == null) throw SearchLeases.invalid();
            JsonNode result = CatalogMcpController.JSON.readTree(text);
            var schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                    .getSchema(outputSchema());
            if (!schema.validate(result).isEmpty()) throw SearchLeases.invalid();
            return result;
        } catch (RuntimeException ex) { throw SearchLeases.invalid(); }
    }
    private static void checkTrace(JsonNode response, SearchLeases.Evidence evidence) {
        int calls = 0;
        for (var item : response.path("output")) {
            String type = item.path("type").asString();
            if (type.equals("mcp_list_tools") && item.hasNonNull("error")) throw new McpDependencyException();
            if (!type.equals("mcp_call")) continue;
            if (++calls > 1 || evidence.search() == null || item.hasNonNull("error")
                    || !"search_catalog".equals(item.path("name").asString())
                    || !"wardrobe_catalog".equals(item.path("server_label").asString())) throw SearchLeases.invalid();
            try {
                var args = CatalogMcpController.parseSearch(CatalogMcpController.JSON.readTree(item.path("arguments").asString()));
                if (!args.equals(evidence.search())) throw SearchLeases.invalid();
            } catch (RuntimeException ex) { throw SearchLeases.invalid(); }
        }
    }
}
