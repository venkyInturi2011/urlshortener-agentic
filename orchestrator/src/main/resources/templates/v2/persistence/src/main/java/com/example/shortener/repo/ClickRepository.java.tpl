package com.example.shortener.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

public interface ClickRepository {

    void record(String code, Instant at);

    long count(String code);

    Optional<Instant> lastClick(String code);

    /** Clicks per calendar day (server-local date), oldest first. */
    Map<LocalDate, Long> countsByDay(String code);
}
