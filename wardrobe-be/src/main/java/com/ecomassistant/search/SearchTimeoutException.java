package com.ecomassistant.search;

/** Deliberately contains no request or dependency payload. */
public final class SearchTimeoutException extends RuntimeException {
    public SearchTimeoutException() {
        super("Search timed out");
    }
}
