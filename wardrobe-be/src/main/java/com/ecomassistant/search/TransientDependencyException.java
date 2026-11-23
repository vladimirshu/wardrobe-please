package com.ecomassistant.search;

import java.time.Duration;

/** A retryable transport failure, with no provider response body. */
public final class TransientDependencyException extends RuntimeException {
    final Duration delay;

    public TransientDependencyException(Duration delay) {
        super("Dependency temporarily unavailable");
        this.delay = delay;
    }
}
