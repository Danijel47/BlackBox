CREATE TABLE warcraft_log_player_run (
    id BIGSERIAL PRIMARY KEY,
    season_key VARCHAR(64) NOT NULL,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    character_name VARCHAR(64) NOT NULL,
    report_code VARCHAR(32) NOT NULL,
    report_revision INTEGER NOT NULL,
    report_started_at TIMESTAMPTZ NOT NULL,
    fight_id INTEGER NOT NULL,
    dungeon_name VARCHAR(128) NOT NULL,
    keystone_level INTEGER NOT NULL,
    interrupts INTEGER NOT NULL,
    deaths INTEGER NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_warcraft_log_player_run
        UNIQUE (season_key, profile_id, report_code, fight_id)
);

CREATE INDEX idx_warcraft_log_player_run_season_profile
    ON warcraft_log_player_run (season_key, profile_id);

CREATE TABLE warcraft_log_profile_snapshot (
    id BIGSERIAL PRIMARY KEY,
    season_key VARCHAR(64) NOT NULL,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    character_name VARCHAR(64) NOT NULL,
    parse_percentage NUMERIC(8, 2),
    last_refreshed_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(512),
    CONSTRAINT uq_warcraft_log_profile_snapshot
        UNIQUE (season_key, profile_id)
);

CREATE INDEX idx_warcraft_log_profile_snapshot_season
    ON warcraft_log_profile_snapshot (season_key, profile_id);
