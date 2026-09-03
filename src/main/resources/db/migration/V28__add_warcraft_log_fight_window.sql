ALTER TABLE warcraft_log_player_run
    ADD COLUMN fight_started_at TIMESTAMPTZ,
    ADD COLUMN fight_ended_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_warcraft_log_player_run_fight_window
        CHECK (
            (fight_started_at IS NULL AND fight_ended_at IS NULL)
            OR (fight_started_at IS NOT NULL
                AND fight_ended_at IS NOT NULL
                AND fight_ended_at > fight_started_at)
        );

CREATE INDEX idx_warcraft_log_player_run_fight_end
    ON warcraft_log_player_run (season_key, profile_id, fight_ended_at);
