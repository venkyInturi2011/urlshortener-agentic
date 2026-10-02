package com.example.shortener.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketLimiterTest {

    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000_000);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1.0, new TestClock());

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void refillsOverTime() {
        TestClock clock = new TestClock();
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 1.0, clock);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        clock.now.addAndGet(1_000);

        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 0.1, new TestClock());
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void reportsRetryAfter() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 0.5, new TestClock());
        limiter.tryAcquire("a");
        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(2);
        assertThat(limiter.retryAfterSeconds("unknown")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketLimiter(0, 1.0, new TestClock())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(1, 0, new TestClock())).isInstanceOf(IllegalArgumentException.class);
    }
}
