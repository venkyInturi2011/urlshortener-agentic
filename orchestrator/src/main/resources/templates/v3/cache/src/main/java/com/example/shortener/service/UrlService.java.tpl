package com.example.shortener.service;

import com.example.shortener.domain.ShortUrl;
import com.example.shortener.error.ShortenerException;
import com.example.shortener.repo.UrlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class UrlService {

    static final int MAX_ATTEMPTS = 5;

    public record CreateResult(ShortUrl url, boolean created) {}

    private final UrlRepository urls;
    private final UrlValidator validator;
    private final CodeGenerator generator;
    private final Clock clock;
    private final RedirectCache cache;

    /** Cache-less variant (cache disabled); keeps unit tests and non-Spring callers simple. */
    public UrlService(UrlRepository urls, UrlValidator validator, CodeGenerator generator, Clock clock) {
        this(urls, validator, generator, clock, RedirectCache.disabled(clock));
    }

    @Autowired
    public UrlService(UrlRepository urls, UrlValidator validator, CodeGenerator generator, Clock clock, RedirectCache cache) {
        this.urls = urls;
        this.validator = validator;
        this.generator = generator;
        this.clock = clock;
        this.cache = cache;
    }

    /**
     * @param expiresAt optional expiry; a link with an expiry is never merged with an existing one,
     *                  because the two would have different lifetimes
     */
    public CreateResult create(String longUrl, String alias, Instant expiresAt) {
        String url = validator.validateUrl(longUrl);
        Instant now = clock.instant();
        Instant expiry = validator.validateExpiry(expiresAt, now);
        if (alias == null || alias.isBlank()) {
            if (expiry == null) {
                Optional<ShortUrl> existing = urls.findGeneratedByLongUrl(url);
                if (existing.isPresent()) return new CreateResult(existing.get(), false);
            }
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ShortUrl candidate = new ShortUrl(generator.next(), url, false, now, expiry, false);
                if (urls.insert(candidate)) return new CreateResult(candidate, true);
            }
            throw ShortenerException.unavailable("could not allocate a unique code, please retry");
        }
        String validAlias = validator.validateAlias(alias);
        if (urls.existsByCodeIgnoreCase(validAlias)) throw ShortenerException.aliasTaken(validAlias);
        ShortUrl candidate = new ShortUrl(validAlias, url, true, now, expiry, false);
        if (!urls.insert(candidate)) throw ShortenerException.aliasTaken(validAlias); // lost a race on the exact code
        return new CreateResult(candidate, true);
    }

    /** @throws ShortenerException 404 if unknown, 410 if deleted or expired */
    public ShortUrl resolve(String code) {
        Optional<ShortUrl> cached = cache.get(code);
        if (cached.isPresent()) {
            assertNotExpired(cached.get()); // expiry is re-checked on every hit
            return cached.get();
        }
        ShortUrl u = urls.findByCode(code).orElseThrow(() -> ShortenerException.notFound(code));
        if (u.deleted()) throw ShortenerException.gone(code);
        assertNotExpired(u);
        cache.put(u);
        return u;
    }

    public void delete(String code) {
        urls.findByCode(code).orElseThrow(() -> ShortenerException.notFound(code));
        urls.softDelete(code);
        cache.evict(code);
    }

    private void assertNotExpired(ShortUrl u) {
        if (u.expiresAt() != null && !u.expiresAt().isAfter(clock.instant())) throw ShortenerException.gone(u.code());
    }
}
