package com.example.shortener.service;

import com.example.shortener.error.ShortenerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @Test
    void httpsUrlIsAccepted() {
        assertThat(validator.validateUrl("  https://example.com/a?b=1  ")).isEqualTo("https://example.com/a?b=1");
    }

    @Test
    void plainHttpPolicy() {
        assertThat(validator.validateUrl("http://example.com/a")).isEqualTo("http://example.com/a");
    }

    @Test
    void publicIpLiteralIsAccepted() {
        assertThat(validator.validateUrl("https://93.184.216.34/x")).isEqualTo("https://93.184.216.34/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file", "javascript:alert(1)", "file:///etc/passwd", "//example.com/x", "example.com",
            "https://user:pass@example.com/", "https:///nohost", "https://exa mple.com/", "   ", ""})
    void rejectsDisallowedSchemesAndMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validateUrl(url)).isInstanceOf(ShortenerException.class)
                .satisfies(e -> assertThat(((ShortenerException) e).status()).isEqualTo(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/", "https://app.localhost/", "https://printer.local/", "https://db.internal/", "https://intranet/",
            "https://127.0.0.1/", "https://10.1.2.3/", "https://172.16.0.1/", "https://192.168.1.10/",
            "https://169.254.169.254/latest/meta-data", "https://0.0.0.0/", "https://100.64.0.1/", "https://224.0.0.1/",
            "https://[::1]/", "https://[fd00::1]/", "https://[fe80::1]/", "https://[::ffff:127.0.0.1]/",
            "https://2130706433/", "https://0x7f.0.0.1/", "https://127.1/", "https://999.1.1.1/"})
    void rejectsInternalAndObfuscatedHosts(String url) {
        assertThatThrownBy(() -> validator.validateUrl(url)).isInstanceOf(ShortenerException.class);
    }

    @Test
    void rejectsOverlongUrl() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_URL_LENGTH);
        assertThatThrownBy(() -> validator.validateUrl(url)).isInstanceOf(ShortenerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "My-Link_01", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void acceptsValidAliases(String alias) {
        assertThat(validator.validateAlias(alias)).isEqualTo(alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "bad/slash", "emoji❤", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "api", "admin", "Admin", "API", "ActuAtor"})
    void rejectsInvalidOrReservedAliases(String alias) {
        assertThatThrownBy(() -> validator.validateAlias(alias)).isInstanceOf(ShortenerException.class);
    }

    @Test
    void expiryMustBeInTheFutureAndWithinFiveYears() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(validator.validateExpiry(null, now)).isNull();
        assertThat(validator.validateExpiry(now.plusSeconds(60), now)).isEqualTo(now.plusSeconds(60));
        assertThatThrownBy(() -> validator.validateExpiry(now, now)).isInstanceOf(ShortenerException.class);
        assertThatThrownBy(() -> validator.validateExpiry(now.minusSeconds(1), now)).isInstanceOf(ShortenerException.class);
        assertThatThrownBy(() -> validator.validateExpiry(now.plus(UrlValidator.MAX_EXPIRY).plus(Duration.ofDays(1)), now))
                .isInstanceOf(ShortenerException.class);
    }
}
