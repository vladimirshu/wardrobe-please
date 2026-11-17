package com.ecomassistant.config;

import com.ecomassistant.guardrails.QueryAnalysis;
import com.ecomassistant.guardrails.QueryAnalysisGuardrailAdvisor;
import com.ecomassistant.guardrails.QueryAnalysisProviderStrategy;
import com.ecomassistant.memory.ExpiringInMemoryChatMemoryRepository;
import com.ecomassistant.memory.ValidatedQueryAnalysisMemoryAdvisor;
import com.ecomassistant.rag.DirectRetrievalAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
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
     * inner) runs: query-analysis guardrail (the only LLM call), retrieval augmentation,
     * and the direct-retrieval short-circuit that returns the retrieved documents without
     * ever invoking the model. No memory advisor: the query-analysis call already
     * compressed history into {@code searchQuery}, and retrieval does not need it.
     */
    @Bean
    public ChatClient productSearchChatClient(ChatModel chatModel,
            QueryAnalysisGuardrailAdvisor queryAnalysisGuardrailAdvisor,
            RetrievalAugmentationAdvisor productSearchRetrievalAdvisor,
            DirectRetrievalAdvisor directRetrievalAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(queryAnalysisGuardrailAdvisor,
                        productSearchRetrievalAdvisor,
                        directRetrievalAdvisor)
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
