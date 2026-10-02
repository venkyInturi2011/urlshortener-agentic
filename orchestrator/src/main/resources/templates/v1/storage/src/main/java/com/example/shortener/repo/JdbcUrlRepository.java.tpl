package com.example.shortener.repo;

import com.example.shortener.domain.ShortUrl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class JdbcUrlRepository implements UrlRepository {

    private static final RowMapper<ShortUrl> MAPPER = (rs, i) -> new ShortUrl(
            rs.getString("code"),
            rs.getString("long_url"),
            rs.getBoolean("custom_alias"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("deleted"));

    private final JdbcTemplate jdbc;

    public JdbcUrlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(ShortUrl url) {
        try {
            jdbc.update("INSERT INTO short_url (code, long_url, custom_alias, created_at, deleted) VALUES (?, ?, ?, ?, ?)",
                    url.code(), url.longUrl(), url.customAlias(), Timestamp.from(url.createdAt()), url.deleted());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<ShortUrl> findByCode(String code) {
        return jdbc.query("SELECT * FROM short_url WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    @Override
    public Optional<ShortUrl> findGeneratedByLongUrl(String longUrl) {
        return jdbc.query("SELECT * FROM short_url WHERE long_url = ? AND custom_alias = FALSE AND deleted = FALSE ORDER BY created_at LIMIT 1",
                MAPPER, longUrl).stream().findFirst();
    }

    @Override
    public void softDelete(String code) {
        jdbc.update("UPDATE short_url SET deleted = TRUE WHERE code = ?", code);
    }
}
