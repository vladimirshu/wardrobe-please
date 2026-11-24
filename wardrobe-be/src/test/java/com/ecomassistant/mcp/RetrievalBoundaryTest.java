package com.ecomassistant.mcp;

import com.ecomassistant.catalog.CatalogSizes;
import com.ecomassistant.retrieval.QdrantRecommendDocumentRetriever;
import com.ecomassistant.retrieval.RetrievalContextKeys;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.*;
import org.springframework.ai.rag.Query;
import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalBoundaryTest {
    @Test void positiveAndNegativesAreOneBatchBoundByReturnedIndices() throws Exception {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.call(any())).thenReturn(new EmbeddingResponse(List.of(
                new Embedding(new float[]{0, 1}, 1), new Embedding(new float[]{1, 0}, 0),
                new Embedding(new float[]{-1, 0}, 2))));
        var qdrant = mock(QdrantClient.class);
        when(qdrant.queryAsync(any(Points.QueryPoints.class))).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(List.of()));
        var retriever = new QdrantRecommendDocumentRetriever(qdrant, embeddings, "test", 10, 0);
        retriever.retrieve(Query.builder().text("jacket").context(Map.of(RetrievalContextKeys.NEGATIVE_EXAMPLES,
                List.of("wool", "down"))).build());
        var request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddings, times(1)).call(request.capture());
        assertThat(request.getValue().getInstructions()).containsExactly("jacket", "wool", "down");
        verify(embeddings, never()).embed(anyString());
        var query = ArgumentCaptor.forClass(Points.QueryPoints.class);
        verify(qdrant).queryAsync(query.capture());
        var recommend = query.getValue().getQuery().getRecommend();
        assertThat(recommend.getPositive(0).getDense().getDataList()).containsExactly(1f, 0f);
        assertThat(recommend.getNegative(0).getDense().getDataList()).containsExactly(0f, 1f);
    }
    @Test void incompleteDuplicateAndInvalidVectorsFailBeforeQdrant() {
        var embeddings = mock(EmbeddingModel.class);
        var qdrant = mock(QdrantClient.class);
        var retriever = new QdrantRecommendDocumentRetriever(qdrant, embeddings, "test", 10, 0);
        for (var response : List.of(new EmbeddingResponse(List.of()),
                new EmbeddingResponse(List.of(new Embedding(new float[]{1}, 4))),
                new EmbeddingResponse(List.of(new Embedding(new float[]{Float.NaN}, 0))))) {
            when(embeddings.call(any())).thenReturn(response);
            assertThatThrownBy(() -> retriever.retrieve(Query.builder().text("jacket").build()))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(qdrant);
    }
    @Test void exactSizesNeverMatchSubstringsAndConstraintsRejectMissingFacts() {
        assertThat(CatalogSizes.tokens(" m,XL,M,42 ")).containsExactly("M", "XL", "42");
        assertThatThrownBy(() -> CatalogSizes.normalize("UK 10" )).isInstanceOf(IllegalArgumentException.class);
        var search = new CatalogSearch("jacket", List.of("wool"), null, "M", false);
        var wrong = Document.builder().text("jacket").metadata(Map.of("sizeTokens", List.of("XL"), "description", "cotton")).build();
        assertThatThrownBy(() -> ExactConstraints.verify(search, wrong)).isInstanceOf(com.ecomassistant.exception.IllegalAssistantResponseException.class);
        var unknown = Document.builder().text("jacket").metadata(Map.of("sizeTokens", List.of("M"), "description", "warm jacket")).build();
        assertThatThrownBy(() -> ExactConstraints.verify(search, unknown)).isInstanceOf(com.ecomassistant.exception.IllegalAssistantResponseException.class);
    }
    @Test void storefrontCannotBeEnabledWithoutEndpointAndPublicHttpsUrl() {
        assertThatThrownBy(() -> new McpProperties(false, true, "", "", 1200, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpProperties(true, true, "http://localhost/api/v1/mcp", "", 1200, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
