CREATE TABLE wow_token_price_snapshot (
    id BIGSERIAL PRIMARY KEY,
    region VARCHAR(8) NOT NULL,
    price_copper BIGINT NOT NULL CHECK (price_copper > 0),
    source_updated_at TIMESTAMPTZ,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_wow_token_price_snapshot_hour UNIQUE (region, captured_at)
);

CREATE INDEX idx_wow_token_price_snapshot_history
    ON wow_token_price_snapshot (region, captured_at DESC);
