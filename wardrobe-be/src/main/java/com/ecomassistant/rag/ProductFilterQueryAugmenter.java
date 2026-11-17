package com.ecomassistant.rag;

import com.ecomassistant.guardrails.PriceConstraint;
import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.retrieval.RetrievalContextKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Augments the (already compressed) user query with the retrieved candidate products and
 * the request's constraints so the product-filtering LLM call can select the matching
 * SKUs.
 *
 * <p>The rendered prompt is {@code prompts/product-filter.md} with the following values:
 * the English {@code searchQuery} (the query text), the customer's original message, the
 * negative examples, a human-readable price constraint and one {@code SKU | title |
 * category | colors | sizes | price | description} line per candidate. Negatives and the
 * original wording are re-injected here because call #1 strips them from
 * {@code searchQuery}.
 */
@Component
public class ProductFilterQueryAugmenter implements QueryAugmenter {

    private static final String TEMPLATE_LOCATION = "prompts/product-filter.md";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(searchQuery|originalMessage|negatives|price|candidates)\\}\\}");

    private final String template;

    public ProductFilterQueryAugmenter() {
        this.template = loadTemplate();
    }

    @Override
    public Query augment(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return query;
        }
        Map<String, Object> context = query.context();

        String searchQuery = query.text();
        String originalMessage = stringOrEmpty(context.get(ProductFilterContextKeys.ORIGINAL_MESSAGE));
        String negatives = renderNegatives(context.get(RetrievalContextKeys.NEGATIVE_EXAMPLES));
        QueryAnalysis analysis = (QueryAnalysis) context.get(ProductFilterContextKeys.QUERY_ANALYSIS);
        String price = renderPrice(analysis == null ? null : analysis.priceConstraint());
        String candidates = documents.stream()
            .map(ProductFilterQueryAugmenter::renderCandidate)
            .collect(Collectors.joining(System.lineSeparator()));

        Map<String, String> values = Map.of("searchQuery", searchQuery, "originalMessage", originalMessage,
                "negatives", negatives, "price", price, "candidates", candidates);
        String rendered = PLACEHOLDER.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(values.get(match.group(1))));

        return new Query(rendered);
    }

    private static String renderCandidate(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String sku = stringOrEmpty(metadata.get("sku"));
        String title = stringOrEmpty(metadata.get("title"));
        String category = stringOrEmpty(metadata.get("category"));
        String colors = stringOrEmpty(metadata.get("colors"));
        String sizes = stringOrEmpty(metadata.get("sizes"));
        String price = renderPriceValue(metadata.get("price"));
        String description = stringOrEmpty(metadata.get("description"));
        return String.join(" | ", sku, title, category, colors, sizes, price, description);
    }

    private static String renderNegatives(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "(none)";
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String renderPrice(PriceConstraint constraint) {
        if (constraint == null || constraint.value() == null) {
            return "(none)";
        }
        String operator = switch (constraint.operator()) {
            case LESS_THAN -> "under";
            case LESS_THAN_OR_EQUAL -> "at most";
            case GREATER_THAN -> "over";
            case GREATER_THAN_OR_EQUAL -> "at least";
        };
        return operator + " " + constraint.value().toPlainString() + " euro";
    }

    private static String renderPriceValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.toPlainString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return value.toString();
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String loadTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_LOCATION);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to load " + TEMPLATE_LOCATION, e);
        }
    }
}
