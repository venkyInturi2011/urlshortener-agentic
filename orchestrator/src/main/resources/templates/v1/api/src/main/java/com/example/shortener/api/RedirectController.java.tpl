package com.example.shortener.api;

import com.example.shortener.domain.ShortUrl;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.UrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    private final UrlService urls;
    private final AnalyticsService analytics;

    public RedirectController(UrlService urls, AnalyticsService analytics) {
        this.urls = urls;
        this.analytics = analytics;
    }

    /** 302 (not 301): permanent redirects are cached by browsers, which would hide clicks and defeat delete/expiry. */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortUrl url = urls.resolve(code);
        try {
            analytics.recordClick(code);
        } catch (RuntimeException e) {
            log.warn("click for {} was not recorded", code, e); // analytics must never break a redirect
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url.longUrl()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
