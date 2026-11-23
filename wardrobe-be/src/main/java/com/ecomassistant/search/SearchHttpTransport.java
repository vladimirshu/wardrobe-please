package com.ecomassistant.search;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class SearchHttpTransport {
    // Search-only client: no invisible connection recovery or redirect sends.
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(2)).readTimeout(SearchBudget.ATTEMPT)
            .writeTimeout(SearchBudget.ATTEMPT).callTimeout(SearchBudget.ATTEMPT)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();

    @Bean
    org.springframework.beans.factory.InitializingBean requireExplicitRetryControl(org.springframework.core.env.Environment environment) {
        return () -> {
            for (String key : java.util.List.of("spring.ai.openai.chat.max-retries",
                    "spring.ai.openai.embedding.max-retries", "spring.ai.retry.max-attempts")) {
                if (environment.getProperty(key, Integer.class, -1) != 0) {
                    throw new IllegalStateException(key + " must be 0; search owns all downstream retries");
                }
            }
        };
    }

    @jakarta.annotation.PreDestroy
    void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
    org.springframework.boot.restclient.RestClientCustomizer searchRestClientTransport() {
        var fallback = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        fallback.setReadTimeout(Duration.ofSeconds(15));
        var factory = new SearchRequestFactory(fallback);
        return builder -> builder.requestFactory(factory);
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer searchOpenAiTransport() {
        return builder -> builder.interceptor(chain -> {
            var budget = SearchBudget.current();
            if (budget == null) return chain.proceed(chain.request());
            var call = client.newCall(chain.request());
            call.timeout().timeout(Math.min(SearchBudget.ATTEMPT.toNanos(), budget.remainingNanos()), TimeUnit.NANOSECONDS);
            try (var registration = budget.onCancel(call::cancel); Response response = call.execute()) {
                if (retryableStatus(response.code())) {
                    throw new TransientDependencyException(retryDelay(response.header("Retry-After")));
                }
                // Read the complete body while the cancellation hook and total call timeout are active.
                ResponseBody body = response.body();
                byte[] bytes = body == null ? new byte[0] : body.bytes();
                budget.check();
                return response.newBuilder().body(ResponseBody.create(bytes, body == null ? null : body.contentType())).build();
            } catch (IOException | RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IOException("Dependency call cancelled", ex);
            }
        });
    }

    static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }
    static Duration retryDelay(String value) {
        if (value != null) {
            try { return Duration.ofSeconds(Math.max(0, Long.parseLong(value))); }
            catch (RuntimeException ignored) {
                try {
                    Duration delay = Duration.between(java.time.Instant.now(),
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                    return delay.isNegative() ? Duration.ZERO : delay;
                } catch (RuntimeException invalidHeader) { /* Use the normal short backoff. */ }
            }
        }
        return Duration.ofMillis(100);
    }
}
