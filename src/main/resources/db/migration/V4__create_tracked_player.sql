CREATE TABLE tracked_player (
    id BIGSERIAL PRIMARY KEY,
    region VARCHAR(8) NOT NULL,
    realm VARCHAR(128) NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    season_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    vault_watch_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title_watch_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title_zero_point_one_watch_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_tracked_player_character UNIQUE (region, realm, character_name)
);

CREATE INDEX idx_tracked_player_active_display_order
    ON tracked_player (active, display_order, id);

INSERT INTO tracked_player (
    region,
    realm,
    character_name,
    display_order,
    active,
    season_recap_enabled,
    vault_watch_enabled,
    title_watch_enabled,
    title_zero_point_one_watch_enabled
) VALUES
    ('eu', 'stormscale', 'Bucothered', 1, TRUE, TRUE, TRUE, TRUE, FALSE),
    ('eu', 'stormscale', 'Lazozero', 2, TRUE, TRUE, TRUE, TRUE, FALSE),
    ('eu', 'stormscale', 'Linqq', 3, TRUE, TRUE, TRUE, TRUE, FALSE),
    ('eu', 'darksorrow', 'Felmm', 4, TRUE, TRUE, TRUE, TRUE, FALSE),
    ('eu', 'doomhammer', 'Zsmoel', 5, TRUE, TRUE, TRUE, TRUE, FALSE),
    ('eu', 'tarren-mill', 'Polivé', 6, TRUE, TRUE, TRUE, TRUE, TRUE);
