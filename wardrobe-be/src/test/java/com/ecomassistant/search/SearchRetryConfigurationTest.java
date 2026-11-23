package com.ecomassistant.search;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SearchRetryConfigurationTest {
    @Test void rejectsIndependentRetryOverridesForEveryLayer() throws Exception {
        var keys = java.util.List.of("spring.ai.openai.chat.max-retries",
                "spring.ai.openai.embedding.max-retries", "spring.ai.retry.max-attempts");
        var config = new SearchHttpTransport();
        try {
            var environment = new MockEnvironment();
            keys.forEach(key -> environment.setProperty(key, "0"));
            config.requireExplicitRetryControl(environment).afterPropertiesSet();
            for (String key : keys) {
                environment.setProperty(key, "1");
                assertThatThrownBy(() -> config.requireExplicitRetryControl(environment).afterPropertiesSet())
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
                environment.setProperty(key, "0");
            }
        } finally { config.close(); }
    }
}
