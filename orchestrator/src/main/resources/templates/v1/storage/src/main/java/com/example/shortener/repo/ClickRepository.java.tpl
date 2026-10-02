package com.example.shortener.repo;

import java.time.Instant;
import java.util.Optional;

public interface ClickRepository {

    void record(String code, Instant at);

    long count(String code);

    Optional<Instant> lastClick(String code);
}
