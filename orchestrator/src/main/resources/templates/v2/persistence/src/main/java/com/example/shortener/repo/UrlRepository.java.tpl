package com.example.shortener.repo;

import com.example.shortener.domain.ShortUrl;

import java.util.Optional;

/** Storage port. Implementations must make {@link #insert} atomic with respect to code uniqueness. */
public interface UrlRepository {

    /** @return false if a link with the same code already exists (nothing is written). */
    boolean insert(ShortUrl url);

    Optional<ShortUrl> findByCode(String code);

    /** Existing auto-generated, non-expiring, non-deleted link for this destination, if any. */
    Optional<ShortUrl> findGeneratedByLongUrl(String longUrl);

    /** True if any link already uses this code, ignoring letter case (used to reject look-alike aliases). */
    boolean existsByCodeIgnoreCase(String code);

    void softDelete(String code);
}
