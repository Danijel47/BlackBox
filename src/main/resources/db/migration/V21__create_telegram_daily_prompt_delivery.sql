CREATE TABLE telegram_daily_prompt_delivery (
    prompt_key VARCHAR(100) PRIMARY KEY,
    delivered_on DATE NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
