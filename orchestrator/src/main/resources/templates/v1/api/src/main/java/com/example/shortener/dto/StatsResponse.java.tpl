package com.example.shortener.dto;

import java.time.Instant;

public record StatsResponse(String code, long totalClicks, Instant lastAccessedAt) {}
