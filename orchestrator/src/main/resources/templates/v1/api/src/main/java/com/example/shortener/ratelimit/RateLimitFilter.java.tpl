package com.example.shortener.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rate-limits write requests under /api per client address (X-Forwarded-For is deliberately not trusted). */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketLimiter limiter;

    public RateLimitFilter(TokenBucketLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr();
        if (limiter.tryAcquire(key)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds(key)));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Rate limit exceeded\"}");
    }
}
