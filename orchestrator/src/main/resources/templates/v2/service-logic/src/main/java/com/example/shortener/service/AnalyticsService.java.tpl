package com.example.shortener.service;

import com.example.shortener.repo.ClickRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

@Service
public class AnalyticsService {

    public record Stats(long totalClicks, Instant lastAccessedAt, Map<LocalDate, Long> clicksByDay) {}

    private final ClickRepository clicks;
    private final Clock clock;

    public AnalyticsService(ClickRepository clicks, Clock clock) {
        this.clicks = clicks;
        this.clock = clock;
    }

    public void recordClick(String code) {
        clicks.record(code, clock.instant());
    }

    public Stats stats(String code) {
        return new Stats(clicks.count(code), clicks.lastClick(code).orElse(null), clicks.countsByDay(code));
    }
}
