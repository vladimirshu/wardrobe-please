package com.ecomassistant.search;

import org.springframework.http.*;
import org.springframework.http.client.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** DeepSeek's synchronous RestClient waits on a cancellable, fully buffered HTTP exchange. */
public final class SearchRequestFactory implements ClientHttpRequestFactory {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ClientHttpRequestFactory fallback;
    public SearchRequestFactory(ClientHttpRequestFactory fallback) { this.fallback = fallback; }
    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        var budget = SearchBudget.current();
        if (budget == null) return fallback.createRequest(uri, method);
        budget.check();
        return new AbstractClientHttpRequest() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            @Override public HttpMethod getMethod() { return method; }
            @Override public URI getURI() { return uri; }
            @Override protected OutputStream getBodyInternal(HttpHeaders headers) { return body; }
            @Override protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                var builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofNanos(Math.min(SearchBudget.ATTEMPT.toNanos(), budget.remainingNanos())))
                        .method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                headers.forEach((name, values) -> {
                    if (!name.equalsIgnoreCase("Content-Length")) values.forEach(value -> builder.header(name, value));
                });
                var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                try (var registration = budget.onCancel(() -> pending.cancel(true))) {
                    var response = pending.get(Math.min(SearchBudget.ATTEMPT.toNanos(), budget.remainingNanos()), TimeUnit.NANOSECONDS);
                    budget.check();
                    if (SearchHttpTransport.retryableStatus(response.statusCode())) {
                        throw new TransientDependencyException(SearchHttpTransport.retryDelay(
                                response.headers().firstValue("Retry-After").orElse(null)));
                    }
                    return new ClientHttpResponse() {
                        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }
                        @Override public String getStatusText() { return ""; }
                        @Override public HttpHeaders getHeaders() {
                            var result = new HttpHeaders();
                            response.headers().map().forEach(result::put);
                            return result;
                        }
                        @Override public InputStream getBody() { return new ByteArrayInputStream(response.body()); }
                        @Override public void close() { }
                    };
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Dependency call interrupted");
                } catch (TimeoutException ex) {
                    throw new java.net.SocketTimeoutException("Dependency timed out");
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof java.net.http.HttpTimeoutException)
                        throw new java.net.SocketTimeoutException("Dependency timed out");
                    throw new IOException("Dependency call failed");
                } catch (RuntimeException | IOException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IOException("Dependency call cancelled");
                } finally {
                    pending.cancel(true);
                }
            }
        };
    }
}
