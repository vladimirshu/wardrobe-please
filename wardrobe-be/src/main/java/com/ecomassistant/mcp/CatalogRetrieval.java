package com.ecomassistant.mcp;

import com.ecomassistant.guardrails.QueryAnalysisValidator;
import com.ecomassistant.guardrails.RetrievalParametersMapper;
import com.ecomassistant.listing.CatalogReadiness;
import com.ecomassistant.listing.CatalogUnavailableException;
import com.ecomassistant.retrieval.QdrantRecommendDocumentRetriever;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Service;
import java.util.List;

/** Shared typed entry point; clients cannot supply raw Qdrant filters or result caps. */
@Service
public class CatalogRetrieval {
    private final QdrantRecommendDocumentRetriever retriever;
    private final QueryAnalysisValidator validator;
    private final RetrievalParametersMapper mapper;
    private final CatalogReadiness readiness;
    public CatalogRetrieval(QdrantRecommendDocumentRetriever retriever, QueryAnalysisValidator validator,
            RetrievalParametersMapper mapper, CatalogReadiness readiness) {
        this.retriever = retriever; this.validator = validator; this.mapper = mapper; this.readiness = readiness;
    }
    public List<Document> search(CatalogSearch search) {
        validator.validate(search.analysis());
        if (search.size() != null && !readiness.isReady())
            throw new CatalogUnavailableException("Exact size filtering is unavailable");
        return retriever.retrieve(Query.builder().text(search.searchQuery()).context(mapper.toContext(search.analysis())).build());
    }
}
