CREATE TABLE mplus_run_log_match (
    id BIGSERIAL PRIMARY KEY,
    season_key VARCHAR(64) NOT NULL,
    mplus_run_id BIGINT REFERENCES mplus_observed_run(id) ON DELETE CASCADE,
    report_code VARCHAR(32) NOT NULL,
    report_revision INTEGER NOT NULL CHECK (report_revision >= 0),
    fight_id INTEGER NOT NULL CHECK (fight_id > 0),
    match_status VARCHAR(16) NOT NULL
        CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'AMBIGUOUS', 'REJECTED')),
    match_method VARCHAR(16) NOT NULL
        CHECK (match_method IN ('AUTOMATIC', 'MANUAL')),
    candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    timestamp_delta_ms BIGINT,
    duration_delta_ms BIGINT,
    roster_overlap_count INTEGER CHECK (roster_overlap_count IS NULL OR roster_overlap_count >= 0),
    first_evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    manually_confirmed_by BIGINT,
    manually_confirmed_at TIMESTAMPTZ,
    CONSTRAINT uq_mplus_log_fight_decision UNIQUE (season_key, report_code, fight_id)
);

CREATE UNIQUE INDEX uq_mplus_successful_run_match
    ON mplus_run_log_match (mplus_run_id)
    WHERE match_status = 'MATCHED';

CREATE INDEX idx_mplus_log_match_status
    ON mplus_run_log_match (season_key, match_status);

