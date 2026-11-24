package com.ecomassistant.mcp;

/** Deliberately carries no provider/tool response body or cause. */
public final class McpDependencyException extends RuntimeException {
    public McpDependencyException() { super("Search temporarily unavailable"); }
}
