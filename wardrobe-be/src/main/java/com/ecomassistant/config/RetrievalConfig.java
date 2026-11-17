package com.ecomassistant.config;

import com.ecomassistant.rag.OrderPreservingDocumentJoiner;
import com.ecomassistant.rag.ProductFilterQueryAugmenter;
import com.ecomassistant.retrieval.QdrantRecommendDocumentRetriever;
import io.qdrant.client.QdrantClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalConfig {

    @Bean
    public QdrantRecommendDocumentRetriever qdrantRecommendDocumentRetriever(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:products}") String collectionName,
            EcomSearchProperties searchProperties) {
        return new QdrantRecommendDocumentRetriever(qdrantClient, embeddingModel, collectionName,
                searchProperties.topK(), searchProperties.similarityThreshold());
    }

    /**
     * Runs after query analysis and supplies the Qdrant candidates to SKU selection.
     */
    @Bean
    public RetrievalAugmentationAdvisor productSearchRetrievalAdvisor(
            QdrantRecommendDocumentRetriever qdrantRecommendDocumentRetriever,
            ProductFilterQueryAugmenter productFilterQueryAugmenter) {
        return RetrievalAugmentationAdvisor.builder()
            .documentRetriever(qdrantRecommendDocumentRetriever)
            .documentJoiner(new OrderPreservingDocumentJoiner())
            .queryAugmenter(productFilterQueryAugmenter)
            .order(-300)
            .build();
    }
}
