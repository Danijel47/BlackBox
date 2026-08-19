CREATE TABLE mplus_score_milestone (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    season_key VARCHAR(64) NOT NULL,
    milestone_score NUMERIC(10, 2) NOT NULL CHECK (milestone_score > 0),
    observed_score NUMERIC(10, 2) NOT NULL CHECK (observed_score >= milestone_score),
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mplus_score_milestone UNIQUE (profile_id, season_key, milestone_score)
);

CREATE INDEX idx_mplus_score_milestone_profile_season
    ON mplus_score_milestone (profile_id, season_key, milestone_score);
