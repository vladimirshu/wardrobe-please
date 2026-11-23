package com.ecomassistant.search;

import com.ecomassistant.controller.ProductsController;
import com.ecomassistant.exception.GlobalExceptionHandler;
import com.ecomassistant.limits.*;
import com.ecomassistant.listing.CatalogListingService;
import com.ecomassistant.memory.ConversationLocks;
import com.ecomassistant.service.ProductSearchService;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.qdrant.client.grpc.Collections.*;
import io.qdrant.client.grpc.CollectionsGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SearchBoundaryTest {
    @Test void generic504ChargesAdmissionOnceAndCleansUpRequestContext() throws Exception {
        var service = mock(ProductSearchService.class);
        var admission = mock(SearchAdmission.class);
        var resolver = mock(ClientKeyResolver.class);
        when(resolver.resolve(any())).thenReturn("client");
        AtomicInteger calls = new AtomicInteger();
        when(service.searchProducts(anyString(), anyString())).thenAnswer(invocation -> {
            var budget = SearchBudget.current();
            assertThat(budget).isNotNull();
            return budget.call(() -> {
                if (calls.incrementAndGet() < 3) throw new TransientDependencyException(Duration.ZERO);
                throw new SearchTimeoutException();
            }, true);
        });
        var controller = new ProductsController(service, mock(ChatMemory.class), admission, resolver,
                mock(CatalogListingService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new SearchBudgetFilter()).build();
        mvc.perform(post("/api/v1/search").contentType("application/json")
                .content("{\"message\":\"navy jacket\",\"conversationId\":\"abc\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(content().json("{\"error\":\"Search timed out\"}"));
        verify(admission, times(1)).admit("client");
        verify(service, times(1)).searchProducts("navy jacket", "abc");
        assertThat(calls).hasValue(3);
        assertThat(SearchBudget.current()).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test void filterDiscardsPartialSuccessOnTimeoutAndExcludesHealth() throws Exception {
        var filter = new SearchBudgetFilter();
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/search");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(SearchBudget.current()).isNotNull();
            res.getOutputStream().write("private partial response".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new SearchTimeoutException();
        });
        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Search timed out\"}");
        assertThat(SearchBudget.current()).isNull();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/health"),
                new org.springframework.mock.web.MockHttpServletResponse(), (req, res) ->
                        assertThat(SearchBudget.current()).isNull());
    }

    @Test void conversationLockWaitIsInsideTheSameDeadlineAndCannotStartLaterWork() throws Exception {
        var locks = new ConversationLocks();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> locks.withLock("conversation", () -> {
                locked.countDown();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return "done";
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            try (var budget = new SearchBudget(Duration.ofMillis(150), System::nanoTime)) {
                assertThatThrownBy(() -> locks.withLock("conversation", () -> fail("Must not run")))
                        .isInstanceOf(SearchTimeoutException.class);
            } finally { release.countDown(); }
            holder.get(2, TimeUnit.SECONDS);
        }
        assertThat(locks.withLock("conversation", () -> "released")).isEqualTo("released");
    }

    @Test void grpcDeadlineCancelsTheActualServerCall() throws Exception {
        var cancelled = new CountDownLatch(1);
        var server = ServerBuilder.forPort(0).addService(new CollectionsGrpc.CollectionsImplBase() {
            @Override public void list(ListCollectionsRequest request, StreamObserver<ListCollectionsResponse> observer) {
                Context.current().addListener(context -> cancelled.countDown(), Runnable::run);
            }
        }).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext()
                .disableRetry().intercept(new SearchGrpcInterceptor()).build();
        try {
            try (var budget = new SearchBudget(Duration.ofMillis(250), System::nanoTime)) {
                assertThatThrownBy(() -> budget.call(() -> {
                    try { return CollectionsGrpc.newFutureStub(channel).list(ListCollectionsRequest.getDefaultInstance()).get(); }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                }, false)).isInstanceOf(SearchTimeoutException.class);
            }
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
