package com.ecomassistant.retrieval;

import com.ecomassistant.VectorStoreUtils;
import com.ecomassistant.converter.QdrantFilterExpressionConverter;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorInputFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Retrieves candidate products from Qdrant using a nearest or recommend query driven
 * entirely by {@link Query} context keys.
 *
 * <p>Supported context keys are defined by {@link RetrievalContextKeys}:
 * <ul>
 *     <li>{@code vector_store_filter_expression} - {@link Filter.Expression} or its string form;</li>
 *     <li>{@code ecom_negative_examples} - {@code List<String>} of negative examples;</li>
 *     <li>{@code ecom_sort_by_lowest_price} - {@code Boolean} requesting ascending price ordering.</li>
 * </ul>
 */
public class QdrantRecommendDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(QdrantRecommendDocumentRetriever.class);

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final String collectionName;
    private final int topK;
    private final float similarityThreshold;
    private final QdrantFilterExpressionConverter filterExpressionConverter = new QdrantFilterExpressionConverter();
    private final FilterExpressionTextParser filterExpressionTextParser = new FilterExpressionTextParser();

    public QdrantRecommendDocumentRetriever(QdrantClient qdrantClient, EmbeddingModel embeddingModel,
            String collectionName, int topK, float similarityThreshold) {
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
        this.collectionName = collectionName;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Map<String, Object> context = query.context();

        List<String> negativeExamples = negativeExamples(context);
        Filter.Expression filterExpression = filterExpression(context);
        boolean sortByLowestPrice = sortByLowestPrice(context);

        var inputs = new java.util.ArrayList<String>();
        inputs.add(query.text());
        inputs.addAll(negativeExamples);
        var response = embeddingModel.call(new org.springframework.ai.embedding.EmbeddingRequest(inputs, null));
        if (response == null || response.getResults().size() != inputs.size())
            throw new IllegalStateException("Incomplete query embeddings");
        float[][] vectors = new float[inputs.size()][];
        for (var result : response.getResults()) {
            int index = result.getIndex();
            float[] vector = result.getOutput();
            if (index < 0 || index >= vectors.length || vectors[index] != null || vector == null || vector.length == 0)
                throw new IllegalStateException("Invalid query embedding positions");
            for (float component : vector) if (!Float.isFinite(component))
                throw new IllegalStateException("Invalid query embedding vector");
            vectors[index] = vector;
        }
        for (var vector : vectors) if (vector == null || vector.length != vectors[0].length)
            throw new IllegalStateException("Incomplete query embeddings");
        Points.Query similarityQuery = similarityQuery(vectors);
        Common.Filter filter = filterExpression == null
                ? null : filterExpressionConverter.convertExpression(filterExpression);
        if (context.get("ecom_exact_size") instanceof String size) {
            var combined = Common.Filter.newBuilder();
            if (filter != null) combined.addMust(io.qdrant.client.ConditionFactory.filter(filter));
            filter = combined.addMust(io.qdrant.client.ConditionFactory.matchKeyword(
                    com.ecomassistant.catalog.CatalogSizes.FIELD,
                    com.ecomassistant.catalog.CatalogSizes.normalize(size))).build();
        }

        Points.QueryPoints.Builder queryPoints = Points.QueryPoints.newBuilder()
            .setCollectionName(collectionName)
            .setWithPayload(WithPayloadSelectorFactory.enable(true));

        if (sortByLowestPrice) {
            Points.PrefetchQuery.Builder prefetch = Points.PrefetchQuery.newBuilder()
                .setQuery(similarityQuery)
                .setScoreThreshold(similarityThreshold)
                .setLimit(topK);
            if (filter != null) {
                prefetch.setFilter(filter);
            }
            queryPoints.addPrefetch(prefetch)
                .setQuery(QueryFactory.orderBy("price"))
                .setLimit(topK);
        }
        else {
            queryPoints.setQuery(similarityQuery)
                .setScoreThreshold(similarityThreshold)
                .setLimit(topK);
            if (filter != null) {
                queryPoints.setFilter(filter);
            }
        }

        try {
            long startTime = System.currentTimeMillis();
            List<Points.ScoredPoint> scoredPoints = queryPoints(queryPoints.build());
            log.info("Vector DB query completed in {} ms", (System.currentTimeMillis() - startTime));
            return scoredPoints.stream().map(VectorStoreUtils::qdrantPointToDocument).toList();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while querying Qdrant", e);
        }
        catch (ExecutionException e) {
            throw new IllegalStateException("Failed to query Qdrant", e);
        }
    }

    private List<Points.ScoredPoint> queryPoints(Points.QueryPoints query) throws InterruptedException, ExecutionException {
        var budget = com.ecomassistant.search.SearchBudget.current();
        if (budget == null) return qdrantClient.queryAsync(query).get();
        return budget.call(() -> {
            budget.check();
            var pending = qdrantClient.queryAsync(query);
            try (var registration = budget.onCancel(() -> pending.cancel(true))) {
                return pending.get(budget.remainingNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new com.ecomassistant.search.SearchTimeoutException();
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new com.ecomassistant.search.SearchTimeoutException();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to query Qdrant", ex);
            } finally {
                pending.cancel(true);
            }
        }, false);
    }

    private Points.Query similarityQuery(float[][] vectors) {
        if (vectors.length == 1) return QueryFactory.nearest(VectorInputFactory.vectorInput(vectors[0]));
        var recommend = Points.RecommendInput.newBuilder()
                .addPositive(VectorInputFactory.vectorInput(vectors[0]))
                .setStrategy(Points.RecommendStrategy.BestScore);
        for (int i = 1; i < vectors.length; i++) recommend.addNegative(VectorInputFactory.vectorInput(vectors[i]));
        return QueryFactory.recommend(recommend.build());
    }

    private Filter.Expression filterExpression(Map<String, Object> context) {
        Object value = context.get(RetrievalContextKeys.FILTER_EXPRESSION);
        if (value == null) {
            return null;
        }
        if (value instanceof Filter.Expression expression) {
            return expression;
        }
        if (value instanceof String string) {
            return filterExpressionTextParser.parse(string);
        }
        throw new IllegalArgumentException("Context key '" + RetrievalContextKeys.FILTER_EXPRESSION
                + "' must be a Filter.Expression or String, but was " + value.getClass().getName());
    }

    private static List<String> negativeExamples(Map<String, Object> context) {
        Object value = context.get(RetrievalContextKeys.NEGATIVE_EXAMPLES);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Context key '" + RetrievalContextKeys.NEGATIVE_EXAMPLES
                    + "' must be a List<String>, but was " + value.getClass().getName());
        }
        return list.stream().map(QdrantRecommendDocumentRetriever::requireString).toList();
    }

    private static String requireString(Object value) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException("Negative examples must be strings, but was "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static boolean sortByLowestPrice(Map<String, Object> context) {
        Object value = context.get(RetrievalContextKeys.SORT_BY_LOWEST_PRICE);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Context key '" + RetrievalContextKeys.SORT_BY_LOWEST_PRICE
                    + "' must be a Boolean, but was " + value.getClass().getName());
        }
        return bool;
    }
}
