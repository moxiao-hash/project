-- V41: Add rubric scoring and reviewer evaluation to roadmap artifacts

ALTER TABLE roadmap_artifacts
    ADD COLUMN rubric_score INT NULL,
    ADD COLUMN rubric_feedback VARCHAR(4000) NULL,
    ADD COLUMN sensitive_scan_passed BOOLEAN NULL DEFAULT TRUE,
    ADD COLUMN sensitive_findings VARCHAR(2000) NULL,
    ADD COLUMN accepted_at TIMESTAMP(6) NULL;

ALTER TABLE roadmap_artifact_reviews
    ADD COLUMN score INT NULL,
    ADD COLUMN rubric_breakdown_json VARCHAR(4000) NULL;
