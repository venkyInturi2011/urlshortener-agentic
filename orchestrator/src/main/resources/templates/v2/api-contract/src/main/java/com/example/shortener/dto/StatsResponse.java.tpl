package com.example.shortener.dto;

import java.time.Instant;
import java.util.Map;

/** {@code clicksByDay} maps ISO dates (yyyy-MM-dd, server-local) to click counts. */
public record StatsResponse(String code, long totalClicks, Instant lastAccessedAt, Map<String, Long> clicksByDay) {}
