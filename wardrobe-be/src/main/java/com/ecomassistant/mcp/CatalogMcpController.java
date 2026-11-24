package com.ecomassistant.mcp;

import com.ecomassistant.limits.SearchAdmission;
import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.search.SearchTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stateless Streamable HTTP, JSON responses; no SSE sessions or conversation locks. */
@RestController
@ConditionalOnProperty(name = "ecom.mcp.enabled", havingValue = "true")
@RequestMapping("/api/v1/mcp")
public class CatalogMcpController {
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    private final McpProperties properties;
    private final SearchLeases leases;
    private final CatalogRetrieval retrieval;
    private final SearchAdmission admission;
    public CatalogMcpController(McpProperties properties, SearchLeases leases, CatalogRetrieval retrieval,
            SearchAdmission admission) {
        this.properties = properties; this.leases = leases; this.retrieval = retrieval; this.admission = admission;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<?> noStream(HttpServletRequest request) {
        var identity = authenticate(request);
        if (identity == null) return unauthorized();
        if (!validOrigin(request)) return ResponseEntity.status(403).build();
        return ResponseEntity.status(405).header("Allow", "POST").build();
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> receive(HttpServletRequest request) throws java.io.IOException {
        Identity identity = authenticate(request);
        if (identity == null) return unauthorized();
        if (!validOrigin(request)) return ResponseEntity.status(403).build();
        String accept = request.getHeader("Accept");
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream"))
            return ResponseEntity.status(406).build();
        String version = request.getHeader("MCP-Protocol-Version");
        if (version != null && !VERSIONS.contains(version)) return ResponseEntity.badRequest().build();
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) { fail(identity); return ResponseEntity.status(413).build(); }
        JsonNode rpc;
        Object id = null;
        try {
            rpc = JSON.readTree(body);
            if (rpc == null || !rpc.isObject() || !"2.0".equals(rpc.path("jsonrpc").asString())
                    || !rpc.path("method").isString() || !only(rpc, Set.of("jsonrpc", "id", "method", "params")))
                throw new IllegalArgumentException();
            if (rpc.has("id")) {
                if (!(rpc.get("id").isString() || rpc.get("id").isNumber())) throw new IllegalArgumentException();
                id = rpc.get("id");
            }
        } catch (RuntimeException ex) {
            fail(identity); return error(null, -32700, "Invalid JSON-RPC request");
        }
        String method = rpc.path("method").asString();
        if (id == null) {
            if (method.equals("notifications/initialized") || method.equals("notifications/cancelled"))
                return ResponseEntity.accepted().build();
            return ResponseEntity.badRequest().build();
        }
        if (method.equals("initialize")) {
            String requested = rpc.path("params").path("protocolVersion").asString();
            return ok(id, Map.of("protocolVersion", VERSIONS.contains(requested) ? requested : "2025-11-25",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "wardrobe-catalog", "version", "1.0")));
        }
        if (method.equals("ping")) return ok(id, Map.of());
        if (method.equals("tools/list")) return ok(id, Map.of("tools", List.of(Map.of(
                "name", "search_catalog", "description", "Read-only apparel catalog search. Call once after allowing the clothing request.",
                "inputSchema", inputSchema(), "annotations", Map.of("readOnlyHint", true, "destructiveHint", false)))));
        if (!method.equals("tools/call")) return error(id, -32601, "Method not found");
        JsonNode params = rpc.path("params");
        if (!only(params, Set.of("name", "arguments", "_meta"))
                || !"search_catalog".equals(params.path("name").asString())) {
            fail(identity); return error(id, -32602, "Invalid tool invocation");
        }
        CatalogSearch search;
        try {
            search = parseSearch(params.path("arguments"));
            new com.ecomassistant.guardrails.QueryAnalysisValidator().validate(search.analysis());
        } catch (RuntimeException ex) {
            fail(identity); return error(id, -32602, "Invalid catalog search arguments");
        }
        try {
            if (identity.lease != null) identity.lease.claim();
            else admission.admit("mcp:" + identity.client);
            Duration remaining = identity.lease == null ? SearchBudget.TOTAL
                    : Duration.ofNanos(identity.lease.budget().remainingNanos());
            try (var budget = SearchBudget.bounded(remaining);
                 AutoCloseable cancellation = identity.lease == null ? () -> {} : identity.lease.budget().onCancel(budget::cancel)) {
                var documents = retrieval.search(search);
                budget.check();
                if (identity.lease != null) identity.lease.complete(search, documents);
                var candidates = documents.stream().map(CatalogMcpController::facts).toList();
                var result = Map.of("candidates", candidates);
                return ok(id, Map.of("structuredContent", result, "content", List.of(Map.of("type", "text",
                        "text", JSON.writeValueAsString(result))), "isError", false));
            }
        } catch (SearchTimeoutException ex) {
            fail(identity); return toolError(id, "RETRIEVAL_TIMEOUT");
        } catch (com.ecomassistant.limits.SearchRejectedException ex) {
            fail(identity); return toolError(id, "ADMISSION_REJECTED");
        } catch (com.ecomassistant.exception.IllegalAssistantResponseException ex) {
            fail(identity); return toolError(id, "RETRIEVAL_NOT_PERMITTED");
        } catch (Exception ex) {
            fail(identity); return toolError(id, "CATALOG_UNAVAILABLE");
        }
    }

    static CatalogSearch parseSearch(JsonNode args) {
        var schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(inputSchema());
        if (args == null || !schema.validate(args).isEmpty()) throw new IllegalArgumentException("Invalid catalog arguments");
        var negatives = new java.util.ArrayList<String>();
        for (var item : args.path("negativeExamples")) negatives.add(item.asString());
        var price = args.hasNonNull("priceConstraint") ? JSON.treeToValue(args.get("priceConstraint"),
                com.ecomassistant.guardrails.PriceConstraint.class) : null;
        return new CatalogSearch(args.path("searchQuery").asString(), negatives, price,
                args.hasNonNull("size") ? args.get("size").asString() : null,
                args.path("sortByLowestPrice").asBoolean(false));
    }

    static JsonNode inputSchema() {
        var schema = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
                com.ecomassistant.guardrails.QueryAnalysis.strictJsonSchema());
        var properties = (tools.jackson.databind.node.ObjectNode) schema.get("properties");
        properties.remove("allowed"); properties.remove("rejectionReasons"); properties.remove("suggestedPrompts");
        var required = schema.putArray("required");
        required.add("searchQuery");
        return schema;
    }
    static Map<String, Object> facts(org.springframework.ai.document.Document document) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (String key : List.of("sku", "title", "category", "colors", "sizes", "price", "description")) {
            Object value = document.getMetadata().get(key);
            if (value instanceof String text) value = text.substring(0, Math.min(text.length(), key.equals("description") ? 2000 : 300));
            if (value != null) result.put(key, value);
        }
        return result;
    }
    private Identity authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 512) return null;
        String token = authorization.substring(7);
        var lease = leases.find(token);
        if (lease != null) return new Identity(null, lease);
        for (var entry : properties.clients().entrySet())
            if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), entry.getValue().getBytes(StandardCharsets.UTF_8)))
                return new Identity(entry.getKey(), null);
        return null;
    }
    private boolean validOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        URI url = URI.create(properties.publicUrl());
        return origin.equals(url.getScheme() + "://" + url.getRawAuthority());
    }
    private static boolean only(JsonNode node, Set<String> keys) {
        return node.isObject() && node.propertyNames().stream().allMatch(keys::contains);
    }
    private static void fail(Identity identity) { if (identity.lease != null) identity.lease.fail(); }
    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer").build();
    }
    private static ResponseEntity<?> ok(Object id, Object result) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }
    private static ResponseEntity<?> error(Object id, int code, String message) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("jsonrpc", "2.0"); result.put("id", id); result.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }
    private static ResponseEntity<?> toolError(Object id, String code) {
        return ok(id, Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", code))));
    }
    private record Identity(String client, SearchLeases.Lease lease) {}
}
