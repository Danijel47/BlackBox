CREATE TABLE rwf_notification (
    event_key VARCHAR(200) PRIMARY KEY,
    raid_slug VARCHAR(100) NOT NULL,
    boss_slug VARCHAR(100) NOT NULL,
    guild_name VARCHAR(200) NOT NULL,
    first_defeated_at TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
