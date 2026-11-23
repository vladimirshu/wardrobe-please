package com.ecomassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalChatProviderConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OptionalChatProviderConfig.class);

    @Test
    void openAiStartsWithoutDeepSeekClasses() {
        context.withClassLoader(new FilteredClassLoader("org.springframework.ai.deepseek",
                        "org.springframework.ai.model.deepseek"))
                .withPropertyValues("spring.ai.model.chat=openai")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void selectingMissingProviderExplainsRebuild() {
        context.withClassLoader(new FilteredClassLoader("org.springframework.ai.deepseek",
                        "org.springframework.ai.model.deepseek"))
                .withPropertyValues("spring.ai.model.chat=deepseek")
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("Uncomment spring-ai-starter-model-deepseek")
                        .hasStackTraceContaining("DEEPSEEK_API_KEY"));
    }
}
