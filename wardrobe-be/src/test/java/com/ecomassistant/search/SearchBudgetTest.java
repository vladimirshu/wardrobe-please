package com.ecomassistant.search;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class SearchBudgetTest {
    @Test void monotonicDeadlineIsSharedAndNotResetByStages() {
        var clock = new AtomicLong(100);
        try (var budget = new SearchBudget(Duration.ofSeconds(25), clock::get)) {
            assertThat(budget.remainingNanos()).isEqualTo(Duration.ofSeconds(25).toNanos());
            clock.addAndGet(Duration.ofSeconds(23).toNanos());
            assertThat(budget.call(() -> budget.remainingNanos(), true)).isEqualTo(Duration.ofSeconds(2).toNanos());
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThatThrownBy(() -> budget.call(() -> fail("No later stage"), true))
                    .isInstanceOf(SearchTimeoutException.class);
        }
        assertThat(SearchBudget.current()).isNull();
    }

    @Test void sharesThreeRetriesAcrossDistinctCallsAndSchemaRepeats() {
        try (var budget = new SearchBudget()) {
            AtomicInteger first = new AtomicInteger();
            assertThat(budget.call(() -> {
                if (first.incrementAndGet() <= 2) throw new TransientDependencyException(Duration.ZERO);
                return "ok";
            }, true)).isEqualTo("ok");
            assertThat(budget.tryAdditionalAttempt()).isTrue(); // schema correction, the third extra send
            AtomicInteger later = new AtomicInteger();
            assertThatThrownBy(() -> budget.call(() -> {
                later.incrementAndGet();
                throw new TransientDependencyException(Duration.ZERO);
            }, true)).isInstanceOf(TransientDependencyException.class);
            assertThat(first).hasValue(3);
            assertThat(later).hasValue(1); // initial call is permitted; no extra allowance per stage
            assertThat(budget.tryAdditionalAttempt()).isFalse();
        }
    }

    @Test void doesNotRetryValidationOrAdmissionFailure() {
        try (var budget = new SearchBudget()) {
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> budget.call(() -> {
                calls.incrementAndGet(); throw new IllegalArgumentException("invalid");
            }, true)).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).hasValue(1);
            for (int i = 0; i < 3; i++) assertThat(budget.tryAdditionalAttempt()).isTrue();
        }
    }

    @Test void rejectsRetryAfterThatCannotFitWithoutSleeping() {
        try (var budget = new SearchBudget()) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> budget.call(() -> {
                throw new TransientDependencyException(Duration.ofSeconds(60));
            }, true)).isInstanceOf(SearchTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test void expiryCancelsRegisteredWorkAndInterruptsWaitWithoutDetachingIt() {
        AtomicInteger cancelled = new AtomicInteger();
        long start = System.nanoTime();
        try (var budget = new SearchBudget(Duration.ofMillis(120), System::nanoTime)) {
            assertThatThrownBy(() -> budget.call(() -> {
                try (var registration = budget.onCancel(cancelled::incrementAndGet)) {
                    Thread.sleep(5000);
                    return "late";
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }, true)).isInstanceOf(SearchTimeoutException.class);
        }
        assertThat(cancelled).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test void completedBudgetNeverInterruptsAReusedRequestThread() throws Exception {
        try (var budget = new SearchBudget(Duration.ofMillis(50), System::nanoTime)) { budget.check(); }
        Thread.sleep(100);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
