CREATE TABLE mplus_season_dungeon (
    season_key VARCHAR(64) NOT NULL,
    dungeon_id INTEGER NOT NULL,
    challenge_mode_id INTEGER NOT NULL,
    dungeon_slug VARCHAR(128) NOT NULL,
    dungeon_name VARCHAR(128) NOT NULL,
    dungeon_short_name VARCHAR(32) NOT NULL,
    keystone_timer_seconds INTEGER NOT NULL CHECK (keystone_timer_seconds > 0),
    refreshed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (season_key, dungeon_id),
    CONSTRAINT uq_mplus_season_dungeon_challenge UNIQUE (season_key, challenge_mode_id)
);

CREATE INDEX idx_mplus_season_dungeon_name
    ON mplus_season_dungeon (season_key, dungeon_short_name);

CREATE TABLE mplus_weekly_vault_snapshot (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    season_key VARCHAR(64) NOT NULL,
    reset_period_start TIMESTAMPTZ NOT NULL,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    run_count INTEGER NOT NULL CHECK (run_count >= 0),
    slot_one_level INTEGER CHECK (slot_one_level > 0),
    slot_four_level INTEGER CHECK (slot_four_level > 0),
    slot_eight_level INTEGER CHECK (slot_eight_level > 0),
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_source VARCHAR(16) NOT NULL CHECK (recovery_source IN ('CURRENT', 'PREVIOUS')),
    captured_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT uq_mplus_weekly_vault_period UNIQUE (profile_id, season_key, reset_period_start)
);

CREATE INDEX idx_mplus_weekly_vault_history
    ON mplus_weekly_vault_snapshot (profile_id, season_key, reset_period_start DESC);
