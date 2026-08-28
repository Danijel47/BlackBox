CREATE TABLE warcraft_log_item_level_snapshot (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    character_name VARCHAR(64) NOT NULL,
    observed_date DATE NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    item_level NUMERIC(6, 2) NOT NULL CHECK (item_level > 0),
    report_code VARCHAR(32) NOT NULL,
    fight_id INTEGER NOT NULL CHECK (fight_id > 0),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_warcraft_log_item_level_day UNIQUE (
        profile_id,
        character_name,
        observed_date
    )
);

CREATE INDEX idx_warcraft_log_item_level_profile_time
    ON warcraft_log_item_level_snapshot (profile_id, observed_at DESC);
