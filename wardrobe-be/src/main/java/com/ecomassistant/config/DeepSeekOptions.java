package com.ecomassistant.config;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

/** Loaded only after the optional provider runtime has been checked. */
final class DeepSeekOptions {
    private DeepSeekOptions() { }

    static ChatOptions.Builder<?> builder(int maxOutputTokens) {
        return DeepSeekChatOptions.builder().maxTokens(maxOutputTokens);
    }
}
