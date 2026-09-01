ALTER TABLE roadmap_stage_graduations DROP CHECK ck_stage_graduation_status;
ALTER TABLE roadmap_stage_graduations ADD COLUMN worker_id VARCHAR(100);
ALTER TABLE roadmap_stage_graduations ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE roadmap_stage_graduations ADD COLUMN lease_until TIMESTAMP(6);
ALTER TABLE roadmap_stage_graduations ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE roadmap_stage_graduations ADD COLUMN last_error VARCHAR(1000);
ALTER TABLE roadmap_stage_graduations
    ADD CONSTRAINT ck_stage_graduation_status
        CHECK (status IN ('READY', 'LEASED', 'COMPLETED', 'FAILED'));
ALTER TABLE roadmap_stage_graduations
    ADD CONSTRAINT ck_stage_graduation_attempt_count CHECK (attempt_count BETWEEN 0 AND 3);

CREATE INDEX idx_stage_graduation_claim
    ON roadmap_stage_graduations (status, lease_until, created_at);
