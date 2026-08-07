CREATE TABLE IF NOT EXISTS time_to_go_snapshot (
    id BIGSERIAL PRIMARY KEY,
    route_key VARCHAR(64) NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL,
    departure_time TIMESTAMPTZ NOT NULL,
    distance_meters INTEGER NOT NULL,
    travel_time_seconds INTEGER NOT NULL,
    no_traffic_travel_time_seconds INTEGER NOT NULL,
    historic_traffic_travel_time_seconds INTEGER NOT NULL,
    live_traffic_incidents_travel_time_seconds INTEGER NOT NULL,
    traffic_delay_seconds INTEGER NOT NULL,
    provider VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_time_to_go_snapshot_route_departure
    ON time_to_go_snapshot (route_key, departure_time);

CREATE INDEX IF NOT EXISTS idx_time_to_go_snapshot_sampled_at
    ON time_to_go_snapshot (sampled_at);
