package com.ecomassistant.guardrails;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class DeepSeekQueryAnalysisStrategy implements QueryAnalysisProviderStrategy {
    @Override
    public void configure(ChatClient.Builder builder, String systemInstructions) {
        builder.defaultSystem(systemInstructions + "\n\nReturn a JSON object matching this schema:\n"
                + QueryAnalysis.jsonSchema());
    }

    @Override
    public @Nullable String providerFailureReason(RuntimeException failure) {
        if (failure instanceof NonTransientAiException || failure instanceof TransientAiException
                || failure instanceof RestClientException) {
            return failure.getClass().getSimpleName();
        }
        return null;
    }
}
