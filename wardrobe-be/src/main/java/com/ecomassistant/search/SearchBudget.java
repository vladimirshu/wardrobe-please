package com.ecomassistant.search;

import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Request-thread context; all synchronous search stages share one monotonic deadline. */
public final class SearchBudget implements AutoCloseable {
    public static final Duration TOTAL = Duration.ofSeconds(25);
    public static final Duration ATTEMPT = Duration.ofSeconds(5);
    private static final ThreadLocal<SearchBudget> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1,
            Thread.ofPlatform().daemon().name("search-deadline-", 0).factory());
    private final LongSupplier clock;
    private final long deadline;
    private final Thread owner = Thread.currentThread();
    private final Set<Runnable> cancellations = new HashSet<>();
    private final ScheduledFuture<?> expiry;
    private int retries = 3;
    private boolean expired;
    private boolean closed;
    private boolean interrupted;
    private Attempt attempt;

    public SearchBudget() { this(TOTAL, System::nanoTime); }

    // Injectable monotonic clock and short budgets for deterministic/local tests.
    SearchBudget(Duration total, LongSupplier clock) {
        if (CURRENT.get() != null) throw new IllegalStateException("Search budget already active");
        this.clock = clock;
        this.deadline = clock.getAsLong() + total.toNanos();
        CURRENT.set(this);
        expiry = TIMER.schedule(this::expire, total.toNanos(), TimeUnit.NANOSECONDS);
    }

    public static SearchBudget current() { return CURRENT.get(); }

    public synchronized long remainingNanos() {
        if (expired || closed || deadline - clock.getAsLong() <= 0) throw new SearchTimeoutException();
        return deadline - clock.getAsLong();
    }

    public void check() { remainingNanos(); }

    public synchronized boolean tryAdditionalAttempt() {
        check();
        if (retries == 0) return false;
        retries--;
        return true;
    }

    public synchronized AutoCloseable onCancel(Runnable cancellation) {
        check();
        if (attempt != null && attempt.timedOut) throw new SearchTimeoutException();
        cancellations.add(cancellation);
        return () -> { synchronized (this) { cancellations.remove(cancellation); } };
    }

    private synchronized void expire() {
        if (closed || expired) return;
        expired = true;
        cancelLocalWork();
    }

    private void cancelLocalWork() {
        for (Runnable cancellation : new ArrayList<>(cancellations)) {
            try { cancellation.run(); } catch (RuntimeException ignored) { /* Continue cancelling other calls. */ }
        }
        interrupted = true;
        owner.interrupt();
    }

    private synchronized Attempt beginAttempt(boolean provider) {
        long remaining = remainingNanos();
        attempt = new Attempt(provider ? Math.min(ATTEMPT.toNanos(), remaining) : remaining);
        return attempt;
    }

    public <T> T call(Supplier<T> action, boolean provider) {
        while (true) {
            RuntimeException failure;
            Attempt currentAttempt = beginAttempt(provider);
            try (currentAttempt) {
                T result = action.get();
                check();
                if (currentAttempt.timedOut) throw new SearchTimeoutException();
                return result;
            } catch (RuntimeException ex) {
                check();
                failure = ex;
            }
            boolean timedOut = currentAttempt.timedOut;
            if (!(timedOut || transientFailure(failure)) || !tryAdditionalAttempt()) {
                if (timedOut || timeoutFailure(failure)) throw new SearchTimeoutException();
                throw failure;
            }
            Duration delay = failure instanceof TransientDependencyException transientError
                    ? transientError.delay : Duration.ofMillis(100);
            pause(delay);
        }
    }

    public void pause(Duration delay) {
        if (delay.compareTo(Duration.ofNanos(remainingNanos())) >= 0) throw new SearchTimeoutException();
        try {
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SearchTimeoutException();
        }
        check();
    }

    private static boolean timeoutFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.http.HttpTimeoutException)
                return true;
        }
        return false;
    }

    private static boolean transientFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDependencyException) return true;
            // SDK deserialization failures can contain a Jackson IOException; they are not transport failures.
            if (cause instanceof com.openai.errors.OpenAIException)
                return cause instanceof com.openai.errors.OpenAIIoException;
            if (cause instanceof org.springframework.web.client.RestClientException
                    && !(cause instanceof org.springframework.web.client.ResourceAccessException)) return false;
            if (cause instanceof IOException) return true;
            if (cause instanceof io.grpc.StatusRuntimeException || cause instanceof io.grpc.StatusException) {
                var code = Status.fromThrowable(cause).getCode();
                return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED
                        || code == Status.Code.RESOURCE_EXHAUSTED;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        expiry.cancel(false);
        CURRENT.remove();
        if (interrupted) Thread.interrupted();
    }

    private final class Attempt implements AutoCloseable {
        private final ScheduledFuture<?> timeout;
        private volatile boolean timedOut;
        private boolean finished;

        private Attempt(long nanos) {
            timeout = TIMER.schedule(() -> {
                synchronized (SearchBudget.this) {
                    if (finished || closed || expired) return;
                    if (deadline - clock.getAsLong() <= 0) {
                        expire();
                        return;
                    }
                    timedOut = true;
                    cancelLocalWork();
                }
            }, nanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void close() {
            synchronized (SearchBudget.this) {
                finished = true;
                timeout.cancel(false);
                attempt = null;
                if (interrupted && !expired) {
                    Thread.interrupted();
                    interrupted = false;
                }
            }
        }
    }
}
