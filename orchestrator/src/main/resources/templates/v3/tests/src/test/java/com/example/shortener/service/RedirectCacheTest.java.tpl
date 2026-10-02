package com.example.shortener.service;

import com.example.shortener.domain.ShortUrl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectCacheTest {

    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000_000);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }

    private static ShortUrl link(String code) {
        return new ShortUrl(code, "https://example.com/" + code, false, Instant.EPOCH, null, false);
    }

    @Test
    void servesEntriesWithinTtlAndDropsThemAfter() {
        TestClock clock = new TestClock();
        RedirectCache cache = new RedirectCache(10, 100, clock);
        cache.put(link("abc1234"));

        assertThat(cache.get("abc1234")).isPresent();
        clock.now.addAndGet(9_999);
        assertThat(cache.get("abc1234")).isPresent();
        clock.now.addAndGet(1);
        assertThat(cache.get("abc1234")).isEmpty();
    }

    @Test
    void evictRemovesEntry() {
        RedirectCache cache = new RedirectCache(60, 100, new TestClock());
        cache.put(link("abc1234"));
        cache.evict("abc1234");
        assertThat(cache.get("abc1234")).isEmpty();
    }

    @Test
    void disabledCacheNeverStores() {
        RedirectCache cache = RedirectCache.disabled(new TestClock());
        cache.put(link("abc1234"));
        assertThat(cache.get("abc1234")).isEmpty();
    }

    @Test
    void sizeIsBounded() {
        RedirectCache cache = new RedirectCache(60, 2, new TestClock());
        cache.put(link("aaa1111"));
        cache.put(link("bbb2222"));
        cache.put(link("ccc3333"));
        assertThat(cache.get("ccc3333")).isPresent();
        assertThat(cache.get("aaa1111")).isEmpty();
    }
}
