package com.ecomassistant.guardrails;

import com.ecomassistant.exception.IllegalAssistantResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Runs the single query-analysis LLM call and returns its structured {@link QueryAnalysis}.
 *
 * <p>The query-analysis client retries schema-invalid responses and commits a turn only
 * when the final response is schema-valid and convertible. Semantic validation is performed
 * separately by {@link QueryAnalysisValidator}, after the turn has been stored.
 */
@Component
public class QueryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalyzer.class);
    private static final BeanOutputConverter<QueryAnalysis> OUTPUT = new BeanOutputConverter<>(QueryAnalysis.class);

    private final ChatClient queryAnalysisChatClient;
    private final QueryAnalysisProviderStrategy strategy;

    public QueryAnalyzer(@Qualifier("queryAnalysisChatClient") ChatClient queryAnalysisChatClient,
            QueryAnalysisProviderStrategy strategy) {
        this.queryAnalysisChatClient = queryAnalysisChatClient;
        this.strategy = strategy;
    }

    public QueryAnalysis analyze(String message, String conversationId) {
        UserMessage userMessage = UserMessage.builder().text(message).build();
        Prompt prompt = Prompt.builder().messages(userMessage).build();
        long startTime = System.currentTimeMillis();
        try {
            var response = queryAnalysisChatClient.prompt(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatResponse();
            QueryAnalysis analysis = OUTPUT.convert(response.getResult().getOutput().getText());
            var metadata = response.getMetadata();
            var usage = metadata.getUsage();
            log.debug("Query analysis token usage: model={}, input={}, output={}, total={}",
                    metadata.getModel(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            log.info("LLM query analysis completed in {} ms", (System.currentTimeMillis() - startTime));
            return analysis;
        } catch (JacksonException e) {
            throw new IllegalAssistantResponseException(
                    "Query analysis response does not match the expected schema", e.getMessage());
        } catch (RuntimeException e) {
            String reason = e instanceof com.ecomassistant.search.TransientDependencyException
                    ? "transport retry budget exhausted" : strategy.providerFailureReason(e);
            if (reason == null) {
                throw e;
            }
            log.warn("Query analysis provider failed: {}", reason);
            throw new IllegalAssistantResponseException("Query analysis provider failed", reason);
        }
    }
}
