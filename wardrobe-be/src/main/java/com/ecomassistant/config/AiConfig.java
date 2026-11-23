package com.ecomassistant.config;

import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.guardrails.QueryAnalysisGuardrailAdvisor;
import com.ecomassistant.guardrails.QueryAnalysisProviderStrategy;
import com.ecomassistant.memory.ExpiringInMemoryChatMemoryRepository;
import com.ecomassistant.memory.ValidatedQueryAnalysisMemoryAdvisor;
import com.ecomassistant.rag.EmptyRetrievalAdvisor;
import com.ecomassistant.rag.ProductSelection;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;

@Configuration
public class AiConfig {
    public static final int MAX_MESSAGES = 20;

    /**
     * Conversation memory model: the client owns the token ({@code conversationId}, a random
     * UUID recommended), history is kept per token for the query-analysis call only, windowed
     * to the last {@link #MAX_MESSAGES} messages, and dropped after 24 hours (configurable)
     * without a new turn. Memory is in-process only: lost on restart and not shared between
     * instances. Replace this bean with a persistent repository to change that.
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(Clock clock, EcomMemoryProperties properties) {
        return new ExpiringInMemoryChatMemoryRepository(clock, properties);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder().maxMessages(MAX_MESSAGES).chatMemoryRepository(chatMemoryRepository).build();
    }

    @Bean
    public StructuredOutputValidationAdvisor queryAnalysisSchemaValidationAdvisor() {
        return StructuredOutputValidationAdvisor.builder()
            .outputJsonSchema(QueryAnalysis.jsonSchema())
            .maxRepeatAttempts(1)
            .build();
    }

    @Bean
    public ChatClient queryAnalysisChatClient(ChatModel chatModel, ChatMemory chatMemory,
            QueryAnalysisProviderStrategy strategy) {
        var builder = ChatClient.builder(chatModel);
        strategy.configure(builder, loadSystemInstructions());
        return builder
                .defaultAdvisors(queryAnalysisSchemaValidationAdvisor(),
                        new ValidatedQueryAnalysisMemoryAdvisor(chatMemory, strategy))
                .build();
    }

    /**
     * The single chat client backing the search endpoint. Its advisor chain (outer to
     * inner) runs query analysis, retrieval, and SKU selection if candidates exist.
     * Only query analysis uses conversation memory.
     */
    @Bean
    public ChatClient productSearchChatClient(ChatModel chatModel,
            QueryAnalysisGuardrailAdvisor queryAnalysisGuardrailAdvisor,
            RetrievalAugmentationAdvisor productSearchRetrievalAdvisor,
            EmptyRetrievalAdvisor emptyRetrievalAdvisor,
            @Value("${spring.ai.model.chat}") String provider,
            @Value("${ecom.search.product-filter-max-output-tokens}") int maxOutputTokens) {
        ChatOptions.Builder<?> options = switch (provider) {
            case "openai" -> OpenAiChatOptions.builder()
                    .maxCompletionTokens(maxOutputTokens).outputSchema(ProductSelection.SCHEMA);
            case "deepseek" -> DeepSeekOptions.builder(maxOutputTokens);
            default -> throw new IllegalStateException("Unsupported storefront chat provider: " + provider);
        };
        return ChatClient.builder(chatModel)
                .defaultOptions(options)
                .defaultAdvisors(queryAnalysisGuardrailAdvisor,
                        productSearchRetrievalAdvisor,
                        emptyRetrievalAdvisor,
                        StructuredOutputValidationAdvisor.builder()
                                .outputJsonSchema(ProductSelection.SCHEMA)
                                .maxRepeatAttempts(1)
                                .advisorOrder(-100)
                                .build())
                .build();
    }

    private static String loadSystemInstructions() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/query-analysis.md");
            return new String(resource.getInputStream().readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load system instructions", e);
        }
    }
}
