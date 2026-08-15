CREATE TABLE wow_tuning_news_item (
    source_guid VARCHAR(512) PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    url VARCHAR(1024) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    notification_sent BOOLEAN NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ
);

CREATE INDEX idx_wow_tuning_news_item_published_at
    ON wow_tuning_news_item (published_at DESC);
