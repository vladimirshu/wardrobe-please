package com.ecomassistant.search;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.time.Duration;

/** Applied only to storefront clients, leaving model use outside search unchanged. */
public final class SearchChatModel implements ChatModel {
    private final ChatModel delegate;
    public SearchChatModel(ChatModel delegate) { this.delegate = delegate; }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public ChatResponse call(Prompt prompt) {
        var budget = SearchBudget.current();
        if (budget == null) return delegate.call(prompt);
        return budget.call(() -> {
            Prompt bounded = prompt;
            ChatOptions effective = prompt.getOptions() == null ? delegate.getOptions() : prompt.getOptions();
            if (effective instanceof OpenAiChatOptions options) {
                bounded = prompt.mutate().chatOptions(options.mutate()
                        .timeout(Duration.ofNanos(Math.min(SearchBudget.ATTEMPT.toNanos(), budget.remainingNanos())))
                        .maxRetries(0).build()).build();
            }
            return delegate.call(bounded);
        }, true);
    }
}
