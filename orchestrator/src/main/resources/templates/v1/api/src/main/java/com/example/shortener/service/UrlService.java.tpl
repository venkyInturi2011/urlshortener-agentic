package com.example.shortener.service;

import com.example.shortener.domain.ShortUrl;
import com.example.shortener.error.ShortenerException;
import com.example.shortener.repo.UrlRepository;
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

    public UrlService(UrlRepository urls, UrlValidator validator, CodeGenerator generator, Clock clock) {
        this.urls = urls;
        this.validator = validator;
        this.generator = generator;
        this.clock = clock;
    }

    public CreateResult create(String longUrl, String alias) {
        String url = validator.validateUrl(longUrl);
        Instant now = clock.instant();
        if (alias == null || alias.isBlank()) {
            Optional<ShortUrl> existing = urls.findGeneratedByLongUrl(url);
            if (existing.isPresent()) return new CreateResult(existing.get(), false);
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ShortUrl candidate = new ShortUrl(generator.next(), url, false, now, false);
                if (urls.insert(candidate)) return new CreateResult(candidate, true);
            }
            throw ShortenerException.unavailable("could not allocate a unique code, please retry");
        }
        String validAlias = validator.validateAlias(alias);
        ShortUrl candidate = new ShortUrl(validAlias, url, true, now, false);
        if (!urls.insert(candidate)) throw ShortenerException.aliasTaken(validAlias);
        return new CreateResult(candidate, true);
    }

    /** @throws ShortenerException 404 if unknown, 410 if deleted */
    public ShortUrl resolve(String code) {
        ShortUrl u = urls.findByCode(code).orElseThrow(() -> ShortenerException.notFound(code));
        if (u.deleted()) throw ShortenerException.gone(code);
        return u;
    }

    public void delete(String code) {
        urls.findByCode(code).orElseThrow(() -> ShortenerException.notFound(code));
        urls.softDelete(code);
    }
}
