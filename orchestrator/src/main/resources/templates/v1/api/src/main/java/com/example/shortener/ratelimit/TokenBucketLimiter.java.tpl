package com.example.shortener.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key token bucket. In-memory: limits are per instance, not global (see risk register). */
public class TokenBucketLimiter {

    private static final int PURGE_THRESHOLD = 10_000;
    private static final long IDLE_PURGE_MS = 10 * 60_000L;

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int capacity, double refillPerSecond, Clock clock) {
        if (capacity < 1 || refillPerSecond <= 0) throw new IllegalArgumentException("capacity >= 1 and refill > 0 required");
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() > PURGE_THRESHOLD) buckets.values().removeIf(b -> now - b.lastRefillMs > IDLE_PURGE_MS);
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;
            nb.lastRefillMs = now;
            return nb;
        });
        synchronized (b) {
            refill(b, now);
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    public long retryAfterSeconds(String key) {
        Bucket b = buckets.get(key);
        if (b == null) return 1;
        synchronized (b) {
            refill(b, clock.millis());
            return Math.max(1, (long) Math.ceil((1 - b.tokens) / refillPerSecond));
        }
    }

    private void refill(Bucket b, long now) {
        double add = (now - b.lastRefillMs) / 1000.0 * refillPerSecond;
        b.tokens = Math.min(capacity, b.tokens + add);
        b.lastRefillMs = now;
    }
}
