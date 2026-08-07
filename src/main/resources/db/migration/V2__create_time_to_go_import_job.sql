CREATE TABLE IF NOT EXISTS time_to_go_import_job (
    id BIGSERIAL PRIMARY KEY,
    import_key VARCHAR(128) NOT NULL,
    tomtom_job_id VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(64) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    saved_at TIMESTAMPTZ,
    message TEXT
);

CREATE INDEX IF NOT EXISTS idx_time_to_go_import_job_status
    ON time_to_go_import_job (status);

CREATE INDEX IF NOT EXISTS idx_time_to_go_import_job_import_key
    ON time_to_go_import_job (import_key);
