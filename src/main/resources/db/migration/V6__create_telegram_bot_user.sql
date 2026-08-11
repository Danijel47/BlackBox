CREATE TABLE telegram_bot_user (
    telegram_user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(128),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_telegram_bot_user_active
    ON telegram_bot_user (active, telegram_user_id);
