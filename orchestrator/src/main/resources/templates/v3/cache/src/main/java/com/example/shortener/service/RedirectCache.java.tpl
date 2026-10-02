package com.example.shortener.service;

import com.example.shortener.domain.ShortUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process TTL cache of live links to keep redirects off the database. Only non-deleted links are cached and
 * deletes evict locally, but other instances can serve a deleted link for up to the TTL (documented trade-off).
 */
@Component
public class RedirectCache {

    private record Entry(ShortUrl url, long validUntilMs) {}

    private final long ttlMs;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public RedirectCache(@Value("${shortener.cache.ttl-seconds:@@CACHE_TTL@@}") long ttlSeconds,
                         @Value("${shortener.cache.max-entries:10000}") int maxEntries,
                         Clock clock) {
        this.ttlMs = ttlSeconds * 1000;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public static RedirectCache disabled(Clock clock) {
        return new RedirectCache(0, 0, clock);
    }

    public Optional<ShortUrl> get(String code) {
        if (ttlMs <= 0) return Optional.empty();
        Entry e = entries.get(code);
        if (e == null) return Optional.empty();
        if (clock.millis() >= e.validUntilMs()) {
            entries.remove(code, e);
            return Optional.empty();
        }
        return Optional.of(e.url());
    }

    public void put(ShortUrl url) {
        if (ttlMs <= 0) return;
        if (entries.size() >= maxEntries) entries.clear(); // crude bound; a real LRU is a listed follow-up
        entries.put(url.code(), new Entry(url, clock.millis() + ttlMs));
    }

    public void evict(String code) {
        entries.remove(code);
    }
}
