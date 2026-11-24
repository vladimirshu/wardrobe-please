package com.ecomassistant.guardrails;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Validated query interpretation, used by direct analysis and the MCP workflow.
 *
 * <p>The verdict fields ({@code allowed}, {@code rejectionReasons},
 * {@code suggestedPrompts}) are kept separate from the search fields
 * ({@code searchQuery}, {@code negativeExamples}, {@code priceConstraint},
 * {@code sortByLowestPrice}) so admission, retrieval and response mapping can validate them independently.
 *
 * @param allowed            whether the request is a valid apparel search request
 * @param rejectionReasons   why the request was rejected; empty when allowed
 * @param suggestedPrompts   example prompts to suggest when rejected; empty when allowed
 * @param searchQuery        the compressed, English search query with price and negatives removed
 * @param negativeExamples   negative examples to exclude from the search
 * @param priceConstraint    a single price constraint, or {@code null} when there is none
 * @param sortByLowestPrice  whether to order results by ascending price
 * @param size               optional exact catalog size token
 */
public record QueryAnalysis(
        boolean allowed,
        List<String> rejectionReasons,
        List<String> suggestedPrompts,
        String searchQuery,
        List<String> negativeExamples,
        @Nullable PriceConstraint priceConstraint,
        boolean sortByLowestPrice,
        @Nullable String size) {

    public QueryAnalysis(boolean allowed, List<String> rejectionReasons, List<String> suggestedPrompts,
            String searchQuery, List<String> negativeExamples, PriceConstraint priceConstraint,
            boolean sortByLowestPrice) {
        this(allowed, rejectionReasons, suggestedPrompts, searchQuery, negativeExamples, priceConstraint,
                sortByLowestPrice, null);
    }

    private static final String JSON_SCHEMA = createJsonSchema();
    private static final String STRICT_JSON_SCHEMA = createStrictJsonSchema();

    public static String jsonSchema() {
        return JSON_SCHEMA;
    }

    public static String strictJsonSchema() {
        return STRICT_JSON_SCHEMA;
    }

    private static String createJsonSchema() {
        var schema = (ObjectNode) JacksonUtils.getDefaultJsonMapper()
                .readTree(JsonSchemaGenerator.generateForType(QueryAnalysis.class));
        var properties = (ObjectNode) schema.get("properties");
        properties.putObject("size").putArray("type").add("string").add("null");
        var priceConstraint = properties.remove("priceConstraint");
        // The generated schema treats the optional object as non-null when present.
        var alternatives = properties.putObject("priceConstraint").putArray("anyOf");
        alternatives.add(priceConstraint);
        alternatives.addObject().put("type", "null");
        return schema.toString();
    }

    private static String createStrictJsonSchema() {
        var schema = (ObjectNode) JacksonUtils.getDefaultJsonMapper().readTree(JSON_SCHEMA);
        schema.remove("$schema");
        requireAllProperties(schema);
        requireAllProperties((ObjectNode) schema.at("/properties/priceConstraint/anyOf/0"));
        return schema.toString();
    }

    private static void requireAllProperties(ObjectNode schema) {
        schema.put("additionalProperties", false);
        var required = schema.putArray("required");
        schema.path("properties").propertyNames().forEach(required::add);
    }
}
