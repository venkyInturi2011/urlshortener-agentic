CREATE TABLE IF NOT EXISTS short_url (
    code         VARCHAR(64)   PRIMARY KEY,
    long_url     VARCHAR(2048) NOT NULL,
    custom_alias BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP     NOT NULL,
    deleted      BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_short_url_long_url ON short_url (long_url);

CREATE TABLE IF NOT EXISTS click (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(64) NOT NULL,
    clicked_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_click_code ON click (code);
