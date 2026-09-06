CREATE TABLE wow_prospecting_sample (
    telegram_user_id BIGINT NOT NULL CHECK (telegram_user_id > 0),
    ore_id BIGINT NOT NULL CHECK (ore_id > 0),
    batch_arguments VARCHAR(1500) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (telegram_user_id, ore_id)
);
