package com.example.shortener.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcClickRepository implements ClickRepository {

    private final JdbcTemplate jdbc;

    public JdbcClickRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String code, Instant at) {
        jdbc.update("INSERT INTO click (code, clicked_at) VALUES (?, ?)", code, Timestamp.from(at));
    }

    @Override
    public long count(String code) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM click WHERE code = ?", Long.class, code);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<Instant> lastClick(String code) {
        Timestamp t = jdbc.queryForObject("SELECT MAX(clicked_at) FROM click WHERE code = ?", Timestamp.class, code);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    @Override
    public Map<LocalDate, Long> countsByDay(String code) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT CAST(clicked_at AS DATE) AS click_day, COUNT(*) AS n FROM click WHERE code = ? GROUP BY CAST(clicked_at AS DATE) ORDER BY click_day",
                rs -> {
                    out.put(rs.getDate("click_day").toLocalDate(), rs.getLong("n"));
                }, code);
        return out;
    }
}
