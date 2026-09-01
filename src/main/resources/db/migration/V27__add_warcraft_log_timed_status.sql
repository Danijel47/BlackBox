ALTER TABLE warcraft_log_player_run
    ADD COLUMN timed BOOLEAN;

UPDATE warcraft_log_player_run log_run
SET timed = observed_run.timed
FROM mplus_run_log_match run_match
JOIN mplus_observed_run observed_run ON observed_run.id = run_match.mplus_run_id
WHERE run_match.season_key = log_run.season_key
  AND run_match.report_code = log_run.report_code
  AND run_match.fight_id = log_run.fight_id
  AND run_match.match_status = 'MATCHED';

UPDATE warcraft_log_player_run log_run
SET timed = log_run.keystone_time_ms
    <= dungeon.keystone_timer_seconds::BIGINT * 1000
FROM mplus_season_dungeon dungeon
WHERE log_run.timed IS NULL
  AND log_run.keystone_time_ms > 1
  AND dungeon.season_key = log_run.season_key
  AND LOWER(dungeon.dungeon_name) = LOWER(log_run.dungeon_name);

CREATE INDEX idx_warcraft_log_player_run_timed_level
    ON warcraft_log_player_run (season_key, keystone_level)
    WHERE timed = TRUE;
