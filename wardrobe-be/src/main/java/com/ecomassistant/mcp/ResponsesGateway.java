package com.ecomassistant.mcp;

import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.search.SearchTimeoutException;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One actual HTTP send; redirects, transport retries and follow-up requests are disabled. */
@Component
@ConditionalOnProperty(name = "ecom.mcp.storefront", havingValue = "true")
public class ResponsesGateway {
    private final McpProperties properties;
    private final String key;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2))
            .readTimeout(SearchBudget.TOTAL).writeTimeout(SearchBudget.TOTAL).callTimeout(SearchBudget.TOTAL)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    public ResponsesGateway(McpProperties properties, @Value("${spring.ai.openai.api-key}") String key) {
        this.properties = properties; this.key = key;
    }
    public JsonNode create(Map<String, Object> payload, SearchBudget budget) {
        budget.check();
        var request = new Request.Builder().url(properties.responsesUrl()).header("Authorization", "Bearer " + key)
                .post(RequestBody.create(CatalogMcpController.JSON.writeValueAsBytes(payload), MediaType.get("application/json"))).build();
        var call = client.newCall(request);
        call.timeout().timeout(budget.remainingNanos(), TimeUnit.NANOSECONDS);
        try (var cancellation = budget.onCancel(call::cancel); var response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new McpDependencyException();
            byte[] bytes = response.body().byteStream().readNBytes(1048577);
            budget.check();
            if (bytes.length > 1048576) throw SearchLeases.invalid();
            return CatalogMcpController.JSON.readTree(bytes);
        } catch (IOException ex) {
            budget.check();
            if (Thread.currentThread().isInterrupted() || ex instanceof java.io.InterruptedIOException)
                throw new SearchTimeoutException();
            throw new McpDependencyException();
        } catch (SearchTimeoutException | McpDependencyException | com.ecomassistant.exception.IllegalAssistantResponseException ex) {
            throw ex;
        } catch (RuntimeException ex) { throw SearchLeases.invalid(); }
        catch (Exception ex) { throw new McpDependencyException(); }
    }
    @jakarta.annotation.PreDestroy
    void close() { client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll(); }
}
