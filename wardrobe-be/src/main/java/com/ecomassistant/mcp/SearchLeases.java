package com.ecomassistant.mcp;

import com.ecomassistant.search.SearchBudget;
import com.ecomassistant.exception.IllegalAssistantResponseException;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived capabilities and server-owned evidence, confined to this backend process. */
@Component
public class SearchLeases {
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    public synchronized Lease open(SearchBudget budget) {
        // Also bound the registry in case a caller fails to close a lease.
        leases.values().removeIf(lease -> !lease.active());
        if (leases.size() >= 1000) throw new McpDependencyException();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Lease lease = new Lease(token, budget);
        leases.put(token, lease);
        return lease;
    }
    public Lease find(String token) {
        Lease lease = leases.get(token);
        return lease != null && lease.active() ? lease : null;
    }
    public final class Lease implements AutoCloseable {
        private final String token;
        private final SearchBudget budget;
        private boolean claimed, failed, closed;
        private CatalogSearch search;
        private List<Document> documents;
        private Lease(String token, SearchBudget budget) { this.token = token; this.budget = budget; }
        public String token() { return token; }
        public SearchBudget budget() { return budget; }
        public synchronized boolean active() {
            try { budget.check(); return !closed; } catch (RuntimeException ex) { return false; }
        }
        public synchronized void claim() {
            budget.check();
            if (closed || claimed || failed) { failed = true; throw invalid(); }
            claimed = true;
        }
        public synchronized void fail() { failed = true; }
        public synchronized void complete(CatalogSearch search, List<Document> documents) {
            budget.check();
            if (closed || !claimed || failed) throw invalid();
            this.search = search; this.documents = List.copyOf(documents);
        }
        public synchronized Evidence evidence(boolean allowed) {
            budget.check();
            if (failed || (allowed ? documents == null : claimed)) throw invalid();
            return new Evidence(search, documents == null ? List.of() : documents);
        }
        @Override public synchronized void close() { closed = true; leases.remove(token, this); }
        @Override public String toString() { return "SearchLease"; }
    }
    public record Evidence(CatalogSearch search, List<Document> documents) {}
    public static IllegalAssistantResponseException invalid() {
        return new IllegalAssistantResponseException("Invalid MCP search response", "invalid MCP evidence");
    }
}
