CREATE TABLE material_alert_snapshot (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    next_attempt_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ
);
INSERT INTO material_alert_snapshot (id) VALUES (1);

CREATE TABLE material_alert_price (
    snapshot_id BIGINT NOT NULL REFERENCES material_alert_snapshot(id) ON DELETE CASCADE,
    item_id BIGINT NOT NULL CHECK (item_id > 0),
    price_copper BIGINT NOT NULL CHECK (price_copper > 0),
    PRIMARY KEY (snapshot_id, item_id)
);

CREATE TABLE material_group_alert (
    group_key VARCHAR(32) PRIMARY KEY,
    threshold_percent NUMERIC(12,4) NOT NULL CHECK (threshold_percent > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    pending BOOLEAN NOT NULL DEFAULT FALSE,
    fired BOOLEAN NOT NULL DEFAULT FALSE,
    baseline_at TIMESTAMPTZ,
    baseline_source_at TIMESTAMPTZ,
    notified_at TIMESTAMPTZ
);

CREATE TABLE material_alert_baseline (
    group_key VARCHAR(32) NOT NULL REFERENCES material_group_alert(group_key) ON DELETE CASCADE,
    item_id BIGINT NOT NULL CHECK (item_id > 0),
    price_copper BIGINT NOT NULL CHECK (price_copper > 0),
    PRIMARY KEY (group_key, item_id)
);
