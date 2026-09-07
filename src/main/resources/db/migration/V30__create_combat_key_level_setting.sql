CREATE TABLE wow_combat_key_level_setting (
    setting_id SMALLINT PRIMARY KEY CHECK (setting_id = 1),
    minimum_keystone_level SMALLINT NOT NULL CHECK (minimum_keystone_level BETWEEN 12 AND 18),
    updated_by BIGINT NOT NULL CHECK (updated_by > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
