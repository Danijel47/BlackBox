ALTER TABLE valorant_profile
    ADD COLUMN headshot_match_count INTEGER CHECK (headshot_match_count BETWEEN 0 AND 20),
    ADD COLUMN head_hits BIGINT CHECK (head_hits >= 0),
    ADD COLUMN body_hits BIGINT CHECK (body_hits >= 0),
    ADD COLUMN leg_hits BIGINT CHECK (leg_hits >= 0),
    ADD COLUMN headshot_updated_at TIMESTAMPTZ,
    ADD COLUMN headshot_attempted_at TIMESTAMPTZ,
    ADD COLUMN headshot_refresh_failed BOOLEAN NOT NULL DEFAULT FALSE;
