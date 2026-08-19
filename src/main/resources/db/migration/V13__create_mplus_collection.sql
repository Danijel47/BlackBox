CREATE TABLE mplus_score_snapshot (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    season_key VARCHAR(64) NOT NULL,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    score_all NUMERIC(10, 2),
    score_dps NUMERIC(10, 2),
    score_healer NUMERIC(10, 2),
    score_tank NUMERIC(10, 2),
    raider_io_crawled_at TIMESTAMPTZ,
    captured_period TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mplus_score_snapshot_period UNIQUE (profile_id, season_key, captured_period)
);

CREATE INDEX idx_mplus_score_snapshot_profile_season_time
    ON mplus_score_snapshot (profile_id, season_key, captured_at DESC);

CREATE TABLE mplus_observed_run (
    id BIGSERIAL PRIMARY KEY,
    season_key VARCHAR(64) NOT NULL,
    raider_io_run_id BIGINT NOT NULL,
    dungeon_name VARCHAR(128) NOT NULL,
    dungeon_short_name VARCHAR(32) NOT NULL,
    map_challenge_mode_id INTEGER,
    mythic_level INTEGER NOT NULL CHECK (mythic_level > 0),
    completed_at TIMESTAMPTZ NOT NULL,
    clear_time_ms BIGINT NOT NULL CHECK (clear_time_ms >= 0),
    par_time_ms BIGINT NOT NULL CHECK (par_time_ms >= 0),
    num_keystone_upgrades INTEGER NOT NULL CHECK (num_keystone_upgrades >= 0),
    score NUMERIC(10, 2),
    timed BOOLEAN NOT NULL,
    observed_recent BOOLEAN NOT NULL DEFAULT FALSE,
    observed_best BOOLEAN NOT NULL DEFAULT FALSE,
    observed_weekly BOOLEAN NOT NULL DEFAULT FALSE,
    details_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (details_status IN ('PENDING', 'LOADED', 'UNAVAILABLE')),
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mplus_observed_run UNIQUE (season_key, raider_io_run_id)
);

CREATE INDEX idx_mplus_observed_run_completed
    ON mplus_observed_run (season_key, completed_at DESC);

CREATE INDEX idx_mplus_observed_run_dungeon_level
    ON mplus_observed_run (season_key, dungeon_short_name, mythic_level DESC);

CREATE TABLE mplus_observed_run_profile (
    run_id BIGINT NOT NULL REFERENCES mplus_observed_run(id) ON DELETE CASCADE,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    observed_region VARCHAR(8) NOT NULL,
    observed_realm VARCHAR(128) NOT NULL,
    observed_character_name VARCHAR(64) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, profile_id)
);

CREATE INDEX idx_mplus_observed_run_profile_history
    ON mplus_observed_run_profile (profile_id, last_observed_at DESC);

CREATE TABLE mplus_observed_run_member (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES mplus_observed_run(id) ON DELETE CASCADE,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    class_name VARCHAR(64),
    spec_name VARCHAR(64),
    role VARCHAR(32),
    matched_profile_id BIGINT REFERENCES player_profile(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_mplus_run_member_character_ignore_case
    ON mplus_observed_run_member (
        run_id,
        LOWER(region),
        LOWER(realm),
        LOWER(character_name)
    );

CREATE INDEX idx_mplus_run_member_matched_profile
    ON mplus_observed_run_member (matched_profile_id, run_id);

CREATE TABLE mplus_observed_run_modifier (
    run_id BIGINT NOT NULL REFERENCES mplus_observed_run(id) ON DELETE CASCADE,
    modifier_id INTEGER NOT NULL,
    modifier_name VARCHAR(64) NOT NULL,
    modifier_slug VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, modifier_id)
);

CREATE TABLE mplus_collection_status (
    profile_id BIGINT PRIMARY KEY REFERENCES player_profile(id) ON DELETE CASCADE,
    season_key VARCHAR(64),
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    raider_io_crawled_at TIMESTAMPTZ,
    observed_run_count INTEGER NOT NULL DEFAULT 0 CHECK (observed_run_count >= 0),
    last_error_category VARCHAR(32)
);

CREATE INDEX idx_mplus_collection_status_success
    ON mplus_collection_status (last_success_at);
