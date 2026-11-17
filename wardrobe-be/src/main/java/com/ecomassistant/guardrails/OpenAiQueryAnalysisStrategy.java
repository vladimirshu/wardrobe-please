package com.ecomassistant.guardrails;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class OpenAiQueryAnalysisStrategy implements QueryAnalysisProviderStrategy {
    @Override
    public void configure(ChatClient.Builder builder, String systemInstructions) {
        builder.defaultSystem(systemInstructions)
                .defaultOptions(OpenAiChatOptions.builder().outputSchema(QueryAnalysis.strictJsonSchema()));
    }

    @Override
    public @Nullable String providerFailureReason(RuntimeException failure) {
        if (failure instanceof OpenAIServiceException serviceError) {
            return "HTTP " + serviceError.statusCode();
        }
        return failure instanceof OpenAIException ? failure.getClass().getSimpleName() : null;
    }

    @Override
    public boolean isRefusal(AssistantMessage message) {
        return message.getMetadata().get("refusal") instanceof String refusal && !refusal.isBlank();
    }
}
