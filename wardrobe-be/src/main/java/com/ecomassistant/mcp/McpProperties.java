package com.ecomassistant.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.Map;

@ConfigurationProperties("ecom.mcp")
public record McpProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean storefront,
        @DefaultValue("") String publicUrl,
        @DefaultValue("https://api.openai.com/v1/responses") String responsesUrl,
        @DefaultValue("1200") int maxOutputTokens,
        Map<String, String> clients) {
    public McpProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
        if (storefront && !enabled) throw new IllegalArgumentException("MCP storefront requires ecom.mcp.enabled");
        if (enabled) {
            URI uri = URI.create(publicUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || !"/api/v1/mcp".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("MCP public URL must be HTTPS with path /api/v1/mcp");
            if (clients.values().stream().anyMatch(token -> token.length() < 32)
                    || new java.util.HashSet<>(clients.values()).size() != clients.size())
                throw new IllegalArgumentException("MCP client tokens must be unique and at least 32 characters");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 4096)
            throw new IllegalArgumentException("Invalid MCP output token cap");
    }
    @Override public String toString() { return "McpProperties[enabled=" + enabled + ", storefront=" + storefront + "]"; }
}
