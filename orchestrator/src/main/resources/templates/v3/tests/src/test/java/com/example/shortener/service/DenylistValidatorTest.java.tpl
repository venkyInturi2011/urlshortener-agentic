package com.example.shortener.service;

import com.example.shortener.error.ShortenerException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DenylistValidatorTest {

    private final UrlValidator validator = new UrlValidator("blocked.example, Evil.Test ,");

    @Test
    void blocksDenylistedDomainAndItsSubdomainsCaseInsensitively() {
        for (String url : new String[]{"https://blocked.example/x", "https://a.b.blocked.example/", "https://EVIL.test/"}) {
            assertThatThrownBy(() -> validator.validateUrl(url)).isInstanceOf(ShortenerException.class)
                    .hasMessageContaining("denylisted");
        }
    }

    @Test
    void doesNotBlockLookalikeSuffixes() {
        assertThat(validator.validateUrl("https://notblocked.example/")).isEqualTo("https://notblocked.example/");
        assertThat(validator.validateUrl("https://blocked.example.org/")).isEqualTo("https://blocked.example.org/");
    }

    @Test
    void emptyDenylistBlocksNothing() {
        assertThat(new UrlValidator("").validateUrl("https://blocked.example/x")).isEqualTo("https://blocked.example/x");
        assertThat(new UrlValidator().validateUrl("https://blocked.example/x")).isEqualTo("https://blocked.example/x");
    }
}
