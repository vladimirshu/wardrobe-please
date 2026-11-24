package com.ecomassistant.mcp;

import com.ecomassistant.catalog.CatalogSizes;
import org.springframework.ai.document.Document;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ExactConstraints {
    private static final Set<String> MATERIALS = Set.of("wool", "down", "leather", "cotton", "polyester", "nylon",
            "silk", "linen", "cashmere", "fur", "feather", "acrylic", "viscose", "elastane");
    static void verify(CatalogSearch search, Document document) {
        var metadata = document.getMetadata();
        if (search.size() != null && (!(metadata.get(CatalogSizes.FIELD) instanceof List<?> tokens)
                || !tokens.contains(search.size()))) throw SearchLeases.invalid();
        if (search.priceConstraint() != null) {
            var constraint = search.priceConstraint();
            int comparison;
            try { comparison = new BigDecimal(metadata.get("price").toString()).compareTo(constraint.value()); }
            catch (RuntimeException ex) { throw SearchLeases.invalid(); }
            boolean valid = switch (constraint.operator()) {
                case LESS_THAN -> comparison < 0; case LESS_THAN_OR_EQUAL -> comparison <= 0;
                case GREATER_THAN -> comparison > 0; case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            };
            if (!valid) throw SearchLeases.invalid();
        }
        // Conservative additional check. Semantic selection remains model-owned, but a selected
        // record must provide material facts and cannot mention an explicitly excluded material.
        String facts = (String.valueOf(metadata.getOrDefault("title", "")) + " "
                + metadata.getOrDefault("description", "")).toLowerCase(Locale.ROOT);
        for (String negative : search.negativeExamples()) for (String material : MATERIALS) {
            var word = Pattern.compile("\\b" + material + "\\b");
            if (!word.matcher(negative.toLowerCase(Locale.ROOT)).find()) continue;
            if (word.matcher(facts).find() || MATERIALS.stream().noneMatch(known ->
                    Pattern.compile("\\b" + known + "\\b").matcher(facts).find())) throw SearchLeases.invalid();
        }
    }
}
