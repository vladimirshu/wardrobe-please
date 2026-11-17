package com.ecomassistant.service;

import com.ecomassistant.VectorStoreUtils;
import com.ecomassistant.config.EcomSearchProperties;
import com.ecomassistant.dto.ProductDto;
import com.ecomassistant.dto.ProductDtoMapper;
import com.ecomassistant.dto.SearchInterpretationDto;
import com.ecomassistant.exception.IllegalAssistantResponseException;
import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.guardrails.SkuOutputGuardrail;
import com.ecomassistant.rag.ProductFilterContextKeys;
import com.ecomassistant.rag.ProductSelection;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs query analysis, retrieval and SKU selection, then maps the selected catalog
 * records and validated query analysis to the storefront response.
 */
@Service
public class ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final ChatClient productSearchChatClient;
    private final ProductDtoMapper productDtoMapper;
    private final SkuOutputGuardrail skuOutputGuardrail;
    private final EcomSearchProperties searchProperties;

    public ProductSearchService(@Qualifier("productSearchChatClient") ChatClient productSearchChatClient,
            ProductDtoMapper productDtoMapper, SkuOutputGuardrail skuOutputGuardrail,
            EcomSearchProperties searchProperties) {
        this.productSearchChatClient = productSearchChatClient;
        this.productDtoMapper = productDtoMapper;
        this.skuOutputGuardrail = skuOutputGuardrail;
        this.searchProperties = searchProperties;
    }

    @Timed(value = "product.search", description = "Product search latency")
    public SearchResult searchProducts(String message, String conversationId) {
        long startTime = System.currentTimeMillis();
        Prompt prompt = Prompt.builder().messages(UserMessage.builder().text(message).build()).build();
        ChatClientResponse chatClientResponse = productSearchChatClient
            .prompt(prompt)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .chatClientResponse();

        ChatResponse chatResponse = chatClientResponse.chatResponse();
        List<Document> documents = retrievedDocuments(chatResponse);
        List<Document> selected = documents.isEmpty() ? List.of()
                : skuOutputGuardrail.apply(documents, parseSelection(chatResponse), searchProperties.maxResults());

        List<ProductDto> products = selected.stream()
            .map(VectorStoreUtils::documentToProduct)
            .map(productDtoMapper::toDto)
            .toList();

        SearchInterpretationDto interpretation = interpretation(chatClientResponse);

        log.info("Returning {} products matching the search criteria", products.size());
        log.info("Product search completed in {} ms", (System.currentTimeMillis() - startTime));
        return new SearchResult(products, interpretation);
    }

    // Spring AI's schema advisor returns the last response even after exhausting retries.
    private static ProductSelection parseSelection(ChatResponse response) {
        var result = response.getResult();
        if (result == null || result.getOutput() == null
                || !"STOP".equalsIgnoreCase(result.getMetadata().getFinishReason())
                || result.getOutput().getText() == null) {
            throw invalidSelection();
        }
        try {
            JsonNode json = JSON.readTree(result.getOutput().getText());
            if (json == null || !json.isObject() || json.size() != 1 || !json.path("skus").isArray()) {
                throw invalidSelection();
            }
            List<String> skus = new ArrayList<>();
            for (JsonNode sku : json.path("skus")) {
                if (!sku.isString()) {
                    throw invalidSelection();
                }
                skus.add(sku.asString());
            }
            return new ProductSelection(skus);
        } catch (JacksonException e) {
            throw invalidSelection();
        }
    }

    private static IllegalAssistantResponseException invalidSelection() {
        return new IllegalAssistantResponseException("Product filtering response does not match the expected schema",
                "invalid SKU selection");
    }

    private static SearchInterpretationDto interpretation(ChatClientResponse chatClientResponse) {
        Object value = chatClientResponse.context().get(ProductFilterContextKeys.QUERY_ANALYSIS);
        return value instanceof QueryAnalysis analysis ? SearchInterpretationDto.from(analysis) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> retrievedDocuments(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return List.of();
        }
        Object value = chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        return value instanceof List<?> list ? (List<Document>) list : List.of();
    }
}
