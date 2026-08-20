ALTER TABLE warcraft_log_player_run
    ADD COLUMN keystone_time_ms BIGINT;

-- A historical key parse is evidence that Warcraft Logs ranked the full dungeon run.
UPDATE warcraft_log_player_run
SET keystone_time_ms = 1
WHERE key_parse_percentage IS NOT NULL;

WITH duplicate_runs AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY season_key,
                            profile_id,
                            LOWER(character_name),
                            report_started_at,
                            fight_id
               ORDER BY (key_parse_percentage IS NOT NULL) DESC,
                        (damage_per_second IS NOT NULL) DESC,
                        metrics_version DESC,
                        report_revision DESC,
                        captured_at DESC,
                        id DESC
           ) AS duplicate_position
    FROM warcraft_log_player_run
)
DELETE FROM warcraft_log_player_run run
USING duplicate_runs duplicate
WHERE run.id = duplicate.id
  AND duplicate.duplicate_position > 1;

CREATE UNIQUE INDEX uq_warcraft_log_player_run_upload_fight
    ON warcraft_log_player_run (
        season_key,
        profile_id,
        LOWER(character_name),
        report_started_at,
        fight_id
    );
