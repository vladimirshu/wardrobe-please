package com.ecomassistant.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
class OptionalChatProviderConfig {
    // Fail before any ChatModel-dependent bean can produce a misleading missing-bean error.
    @Bean
    static BeanFactoryPostProcessor requireDeepSeekRuntime() {
        return beanFactory -> {
            if (!ClassUtils.isPresent("org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
                    beanFactory.getBeanClassLoader())) {
                throw new IllegalStateException("DeepSeek chat is selected but its optional runtime is absent. "
                        + "Uncomment spring-ai-starter-model-deepseek in build.gradle.kts, rebuild, "
                        + "and supply DEEPSEEK_API_KEY; OpenAI remains required for embeddings.");
            }
        };
    }
}
