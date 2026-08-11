CREATE TABLE player_profile (
    id BIGSERIAL PRIMARY KEY,
    profile_name VARCHAR(64) NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    season_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    vault_watch_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title_watch_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title_zero_point_one_watch_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_player_profile_name_ignore_case
    ON player_profile (LOWER(profile_name));

CREATE INDEX idx_player_profile_active_display_order
    ON player_profile (active, display_order, id);

CREATE TABLE tracked_character (
    id BIGSERIAL PRIMARY KEY,
    profile_id BIGINT NOT NULL REFERENCES player_profile(id) ON DELETE CASCADE,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_tracked_character_profile_character_ignore_case
    ON tracked_character (
        profile_id,
        LOWER(region),
        LOWER(realm),
        LOWER(character_name)
    );

CREATE UNIQUE INDEX uq_tracked_character_one_selected_per_profile
    ON tracked_character (profile_id)
    WHERE selected;

CREATE INDEX idx_tracked_character_profile_active
    ON tracked_character (profile_id, active, id);

INSERT INTO player_profile (
    profile_name,
    display_order,
    active,
    season_recap_enabled,
    vault_watch_enabled,
    title_watch_enabled,
    title_zero_point_one_watch_enabled
)
SELECT
    character_name,
    display_order,
    active,
    season_recap_enabled,
    vault_watch_enabled,
    title_watch_enabled,
    title_zero_point_one_watch_enabled
FROM tracked_player
ORDER BY display_order, id;

INSERT INTO tracked_character (
    profile_id,
    region,
    realm,
    character_name,
    selected,
    active
)
SELECT
    profile.id,
    player.region,
    player.realm,
    player.character_name,
    TRUE,
    TRUE
FROM tracked_player player
JOIN player_profile profile
    ON LOWER(profile.profile_name) = LOWER(player.character_name);

DROP TABLE tracked_player;
