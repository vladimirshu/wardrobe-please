package com.ecomassistant.mcp;

import com.ecomassistant.guardrails.PriceConstraint;
import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.catalog.CatalogSizes;
import java.util.List;

/** The only retrieval arguments exposed to an MCP caller. */
public record CatalogSearch(String searchQuery, List<String> negativeExamples,
        PriceConstraint priceConstraint, String size, boolean sortByLowestPrice) {
    public CatalogSearch {
        negativeExamples = negativeExamples == null ? List.of() : List.copyOf(negativeExamples);
        size = CatalogSizes.normalize(size);
    }
    public QueryAnalysis analysis() {
        return new QueryAnalysis(true, List.of(), List.of(), searchQuery, negativeExamples,
                priceConstraint, sortByLowestPrice, size);
    }
    public static CatalogSearch from(QueryAnalysis analysis) {
        return new CatalogSearch(analysis.searchQuery(), analysis.negativeExamples(), analysis.priceConstraint(),
                analysis.size(), analysis.sortByLowestPrice());
    }
}
