CREATE TABLE telegram_daily_prompt_activity (
    prompt_key VARCHAR(100) PRIMARY KEY,
    activity_date DATE NOT NULL,
    message_count INTEGER NOT NULL,
    trigger_message_number SMALLINT NOT NULL,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT telegram_daily_prompt_message_count_positive CHECK (message_count > 0),
    CONSTRAINT telegram_daily_prompt_trigger_message_range CHECK (trigger_message_number BETWEEN 2 AND 5)
);
