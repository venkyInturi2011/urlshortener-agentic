package com.example.shortener.service;

import com.example.shortener.domain.ShortUrl;
import com.example.shortener.error.ShortenerException;
import com.example.shortener.repo.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceCacheTest {

    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }

    @Mock UrlRepository repo;
    @Mock CodeGenerator generator;

    private final TestClock clock = new TestClock();
    private UrlService service;

    @BeforeEach
    void setUp() {
        service = new UrlService(repo, new UrlValidator(), generator, clock, new RedirectCache(60, 100, clock));
    }

    @Test
    void secondResolveIsServedFromCache() {
        ShortUrl live = new ShortUrl("abc1234", "https://example.com/a", false, clock.instant(), null, false);
        when(repo.findByCode("abc1234")).thenReturn(Optional.of(live));

        assertThat(service.resolve("abc1234")).isEqualTo(live);
        assertThat(service.resolve("abc1234")).isEqualTo(live);

        verify(repo, times(1)).findByCode("abc1234");
    }

    @Test
    void deleteEvictsCachedEntry() {
        ShortUrl live = new ShortUrl("abc1234", "https://example.com/a", false, clock.instant(), null, false);
        when(repo.findByCode("abc1234")).thenReturn(Optional.of(live), Optional.of(live),
                Optional.of(new ShortUrl("abc1234", live.longUrl(), false, live.createdAt(), null, true)));

        service.resolve("abc1234");
        service.delete("abc1234");

        assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(((ShortenerException) e).status()).isEqualTo(410));
    }

    @Test
    void cachedLinkStillHonoursItsOwnExpiry() {
        ShortUrl expiring = new ShortUrl("abc1234", "https://example.com/a", false, clock.instant(),
                clock.instant().plusSeconds(30), false);
        when(repo.findByCode("abc1234")).thenReturn(Optional.of(expiring));

        service.resolve("abc1234");
        clock.now.addAndGet(31_000); // still inside the 60 s cache TTL, but past the link's expiry

        assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(((ShortenerException) e).status()).isEqualTo(410));
    }

    @Test
    void unknownCodeIsNotCached() {
        when(repo.findByCode("nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(ShortenerException.class);
        assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(ShortenerException.class);

        verify(repo, times(2)).findByCode("nope123");
    }
}
