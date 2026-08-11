CREATE TABLE IF NOT EXISTS raider_io_abandoned_run_import (
    id BIGSERIAL PRIMARY KEY,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    season_slug VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    grouping_dimension VARCHAR(32) NOT NULL,
    stat_type VARCHAR(64) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    source_url TEXT NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    CONSTRAINT uq_raider_io_abandoned_run_import_snapshot
        UNIQUE (region, realm, character_name, season_slug, generated_at)
);

CREATE TABLE IF NOT EXISTS raider_io_abandoned_run_dungeon_stat (
    id BIGSERIAL PRIMARY KEY,
    import_id BIGINT NOT NULL REFERENCES raider_io_abandoned_run_import(id) ON DELETE CASCADE,
    dungeon VARCHAR(128) NOT NULL,
    abandoned_runs INTEGER NOT NULL CHECK (abandoned_runs >= 0),
    live_tracked_runs INTEGER NOT NULL CHECK (live_tracked_runs >= 0),
    abandon_percent NUMERIC(18, 16) NOT NULL CHECK (abandon_percent >= 0 AND abandon_percent <= 1),
    CONSTRAINT ck_raider_io_abandoned_runs_not_greater_than_tracked
        CHECK (abandoned_runs <= live_tracked_runs),
    CONSTRAINT uq_raider_io_abandoned_run_dungeon_per_import
        UNIQUE (import_id, dungeon)
);

CREATE INDEX IF NOT EXISTS idx_raider_io_abandoned_run_import_character_season
    ON raider_io_abandoned_run_import (
        region,
        realm,
        character_name,
        season_slug,
        generated_at DESC
    );

CREATE INDEX IF NOT EXISTS idx_raider_io_abandoned_run_dungeon_stat_import
    ON raider_io_abandoned_run_dungeon_stat (import_id);
