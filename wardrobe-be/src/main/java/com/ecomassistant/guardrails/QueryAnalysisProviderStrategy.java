package com.ecomassistant.guardrails;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;

public interface QueryAnalysisProviderStrategy {
    void configure(ChatClient.Builder builder, String systemInstructions);

    /** Returns a safe diagnostic for provider failures handled by this strategy. */
    @Nullable String providerFailureReason(RuntimeException failure);

    default boolean isRefusal(AssistantMessage message) {
        return false;
    }
}
