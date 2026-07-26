ALTER TABLE materials
    ADD COLUMN original_filename VARCHAR(255),
    ADD COLUMN storage_key VARCHAR(500),
    ADD COLUMN media_type VARCHAR(120),
    ADD COLUMN content_length BIGINT;

CREATE TABLE material_processing_jobs (
    id VARCHAR(36) PRIMARY KEY,
    material_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    worker_id VARCHAR(100),
    lease_expires_at TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_material_processing_job_material UNIQUE (material_id),
    CONSTRAINT fk_material_processing_job_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE INDEX idx_material_jobs_claim
    ON material_processing_jobs (status, lease_expires_at, attempt_count, created_at);
