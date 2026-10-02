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
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    private static final String URL = "https://example.com/page";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock UrlRepository repo;
    @Mock CodeGenerator generator;

    private UrlService service;

    @BeforeEach
    void setUp() {
        service = new UrlService(repo, new UrlValidator(), generator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static int status(Throwable t) {
        return ((ShortenerException) t).status();
    }

    @Test
    void createsNewShortUrl() {
        when(repo.findGeneratedByLongUrl(URL)).thenReturn(Optional.empty());
        when(generator.next()).thenReturn("abc1234");
        when(repo.insert(any())).thenReturn(true);

        UrlService.CreateResult r = service.create(URL, null, null);

        assertThat(r.created()).isTrue();
        assertThat(r.url().code()).isEqualTo("abc1234");
        assertThat(r.url().createdAt()).isEqualTo(NOW);
        assertThat(r.url().customAlias()).isFalse();
    }

    @Test
    void sameLongUrlReturnsExistingLink() {
        ShortUrl existing = new ShortUrl("exist01", URL, false, NOW, null, false);
        when(repo.findGeneratedByLongUrl(URL)).thenReturn(Optional.of(existing));

        UrlService.CreateResult r = service.create(URL, "  ", null);

        assertThat(r.created()).isFalse();
        assertThat(r.url()).isEqualTo(existing);
        verify(repo, never()).insert(any());
    }

    @Test
    void retriesOnCodeCollision() {
        when(repo.findGeneratedByLongUrl(URL)).thenReturn(Optional.empty());
        when(generator.next()).thenReturn("aaaaaaa", "bbbbbbb");
        when(repo.insert(any())).thenReturn(false, true);

        assertThat(service.create(URL, null, null).url().code()).isEqualTo("bbbbbbb");
        verify(generator, times(2)).next();
    }

    @Test
    void failsClosedAfterMaxAttempts() {
        when(repo.findGeneratedByLongUrl(URL)).thenReturn(Optional.empty());
        when(generator.next()).thenReturn("aaaaaaa");
        when(repo.insert(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(URL, null, null)).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(503));
        verify(generator, times(UrlService.MAX_ATTEMPTS)).next();
    }

    @Test
    void customAliasIsStored() {
        when(repo.existsByCodeIgnoreCase("my-link")).thenReturn(false);
        when(repo.insert(any())).thenReturn(true);

        UrlService.CreateResult r = service.create(URL, "my-link", null);

        assertThat(r.url().code()).isEqualTo("my-link");
        assertThat(r.url().customAlias()).isTrue();
    }

    @Test
    void takenAliasIsConflict() {
        when(repo.existsByCodeIgnoreCase("my-link")).thenReturn(false);
        when(repo.insert(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(URL, "my-link", null)).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void invalidUrlNeverReachesRepository() {
        assertThatThrownBy(() -> service.create("ftp://example.com", null, null)).isInstanceOf(ShortenerException.class);
        verify(repo, never()).insert(any());
    }

    @Test
    void resolveUnknownIsNotFound() {
        when(repo.findByCode("nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(404));
    }

    @Test
    void resolveDeletedIsGone() {
        when(repo.findByCode("gone123")).thenReturn(Optional.of(new ShortUrl("gone123", URL, false, NOW, null, true)));

        assertThatThrownBy(() -> service.resolve("gone123")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(410));
    }

    @Test
    void deleteMarksLinkDeleted() {
        when(repo.findByCode("abc1234")).thenReturn(Optional.of(new ShortUrl("abc1234", URL, false, NOW, null, false)));

        service.delete("abc1234");

        verify(repo).softDelete("abc1234");
    }

    @Test
    void deleteUnknownIsNotFound() {
        when(repo.findByCode("nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("nope123")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(404));
        verify(repo, never()).softDelete(any());
    }

    // ---- v1.1.0: expiry and alias case-collision regression

    @Test
    void aliasDifferingOnlyByCaseIsRejectedBeforeInsert() {
        when(repo.existsByCodeIgnoreCase("My-Link")).thenReturn(true);

        assertThatThrownBy(() -> service.create(URL, "My-Link", null)).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        verify(repo, never()).insert(any());
    }

    @Test
    void reservedAliasIsRejectedRegardlessOfCase() {
        for (String alias : new String[]{"admin", "Admin", "API", "ActuAtor"}) {
            assertThatThrownBy(() -> service.create(URL, alias, null)).isInstanceOf(ShortenerException.class)
                    .satisfies(e -> assertThat(status(e)).isEqualTo(400));
        }
        verify(repo, never()).insert(any());
    }

    @Test
    void expiringLinkIsNeverMergedWithAnExistingOne() {
        Instant expiry = NOW.plusSeconds(3600);
        when(generator.next()).thenReturn("exp1234");
        when(repo.insert(any())).thenReturn(true);

        UrlService.CreateResult r = service.create(URL, null, expiry);

        assertThat(r.created()).isTrue();
        assertThat(r.url().expiresAt()).isEqualTo(expiry);
        verify(repo, never()).findGeneratedByLongUrl(any());
    }

    @Test
    void pastExpiryIsRejected() {
        assertThatThrownBy(() -> service.create(URL, null, NOW.minusSeconds(1))).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(400));
        verify(repo, never()).insert(any());
    }

    @Test
    void resolveExpiredIsGone() {
        when(repo.findByCode("old1234")).thenReturn(Optional.of(new ShortUrl("old1234", URL, false, NOW.minusSeconds(100), NOW, false)));

        assertThatThrownBy(() -> service.resolve("old1234")).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(410));
    }

    @Test
    void resolveNotYetExpiredSucceeds() {
        ShortUrl live = new ShortUrl("live123", URL, false, NOW, NOW.plusSeconds(60), false);
        when(repo.findByCode("live123")).thenReturn(Optional.of(live));

        assertThat(service.resolve("live123")).isEqualTo(live);
    }
}
