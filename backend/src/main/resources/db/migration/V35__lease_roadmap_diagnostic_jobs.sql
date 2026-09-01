ALTER TABLE roadmap_diagnostics DROP CHECK ck_roadmap_diagnostic_status;
ALTER TABLE roadmap_diagnostics ADD COLUMN worker_id VARCHAR(100);
ALTER TABLE roadmap_diagnostics ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE roadmap_diagnostics ADD COLUMN lease_until TIMESTAMP(6);
ALTER TABLE roadmap_diagnostics ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE roadmap_diagnostics ADD COLUMN last_error VARCHAR(1000);
ALTER TABLE roadmap_diagnostics
    ADD CONSTRAINT ck_roadmap_diagnostic_status
        CHECK (status IN ('PENDING', 'LEASED', 'READY', 'COMPLETED', 'FAILED'));
ALTER TABLE roadmap_diagnostics
    ADD CONSTRAINT ck_roadmap_diagnostic_attempt_count CHECK (attempt_count BETWEEN 0 AND 3);

CREATE INDEX idx_roadmap_diagnostic_claim
    ON roadmap_diagnostics (status, lease_until, created_at);
