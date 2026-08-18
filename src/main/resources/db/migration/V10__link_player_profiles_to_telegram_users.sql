ALTER TABLE player_profile
    ADD COLUMN telegram_user_id BIGINT;

ALTER TABLE player_profile
    ADD CONSTRAINT fk_player_profile_telegram_user
        FOREIGN KEY (telegram_user_id)
        REFERENCES telegram_bot_user (telegram_user_id)
        ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_player_profile_telegram_user
    ON player_profile (telegram_user_id)
    WHERE telegram_user_id IS NOT NULL;
