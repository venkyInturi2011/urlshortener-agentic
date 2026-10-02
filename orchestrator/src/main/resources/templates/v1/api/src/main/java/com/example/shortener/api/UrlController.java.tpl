package com.example.shortener.api;

import com.example.shortener.domain.ShortUrl;
import com.example.shortener.dto.CreateUrlRequest;
import com.example.shortener.dto.StatsResponse;
import com.example.shortener.dto.UrlResponse;
import com.example.shortener.error.ShortenerException;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlService urls;
    private final AnalyticsService analytics;
    private final String baseUrl;
    private final String adminKey;

    public UrlController(UrlService urls, AnalyticsService analytics,
                         @Value("${shortener.base-url}") String baseUrl,
                         @Value("${shortener.admin-key:}") String adminKey) {
        this.urls = urls;
        this.analytics = analytics;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.adminKey = adminKey;
    }

    @PostMapping
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        UrlService.CreateResult r = urls.create(request.longUrl(), request.customAlias());
        UrlResponse body = toResponse(r.url());
        if (r.created()) return ResponseEntity.created(URI.create("/api/v1/urls/" + r.url().code())).body(body);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{code}")
    public UrlResponse get(@PathVariable String code) {
        return toResponse(urls.resolve(code));
    }

    @GetMapping("/{code}/stats")
    public StatsResponse stats(@PathVariable String code) {
        urls.resolve(code);
        AnalyticsService.Stats s = analytics.stats(code);
        return new StatsResponse(code, s.totalClicks(), s.lastAccessedAt());
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code,
                                       @RequestHeader(value = "X-API-Key", required = false) String key) {
        authorize(key);
        urls.delete(code);
        return ResponseEntity.noContent().build();
    }

    private void authorize(String key) {
        boolean ok = !adminKey.isBlank() && key != null && MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw ShortenerException.forbidden("a valid X-API-Key is required");
    }

    private UrlResponse toResponse(ShortUrl u) {
        return new UrlResponse(u.code(), baseUrl + "/" + u.code(), u.longUrl(), u.createdAt());
    }
}
