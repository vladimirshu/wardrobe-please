package com.ecomassistant.mcp;

import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.search.SearchTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SearchLeasesTest {
    @Test void discoveryDoesNotClaimButRepeatedRetrievalInvalidatesEvidence() {
        var leases = new SearchLeases();
        try (var budget = new SearchBudget(); var lease = leases.open(budget)) {
            assertThat(leases.find(lease.token())).isSameAs(lease);
            assertThat(leases.find(lease.token())).isSameAs(lease);
            lease.claim(); lease.complete(new CatalogSearch("jacket", List.of(), null, null, false), List.of());
            assertThat(lease.evidence(true).documents()).isEmpty();
            assertThatThrownBy(lease::claim).isInstanceOf(com.ecomassistant.exception.IllegalAssistantResponseException.class);
            assertThatThrownBy(() -> lease.evidence(true)).isInstanceOf(com.ecomassistant.exception.IllegalAssistantResponseException.class);
        }
    }
    @Test void closedAndCancelledCapabilitiesCannotBeReused() {
        var leases = new SearchLeases();
        try (var budget = new SearchBudget()) {
            var lease = leases.open(budget); String token = lease.token();
            lease.close(); assertThat(leases.find(token)).isNull();
            try (var next = leases.open(budget)) {
                budget.cancel();
                assertThat(leases.find(next.token())).isNull();
                assertThatThrownBy(next::claim).isInstanceOf(SearchTimeoutException.class);
            }
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(SearchBudget.current()).isNull();
    }
    @Test void parentCancellationCancelsCallbackOnItsOwnThread() throws Exception {
        var parent = new SearchBudget();
        var started = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        try (parent; var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = executor.submit(() -> {
                try (var child = SearchBudget.bounded(Duration.ofNanos(parent.remainingNanos()));
                     var link = parent.onCancel(child::cancel);
                     var hook = child.onCancel(cancelled::countDown)) {
                    started.countDown();
                    try { Thread.sleep(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    assertThatThrownBy(child::check).isInstanceOf(SearchTimeoutException.class);
                } catch (Exception ex) { throw new RuntimeException(ex); }
                assertThat(SearchBudget.current()).isNull();
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
            });
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            parent.cancel();
            // The owner thread is interrupted by its budget, as in an expired HTTP search.
            Thread.interrupted();
            assertThat(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            callback.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
