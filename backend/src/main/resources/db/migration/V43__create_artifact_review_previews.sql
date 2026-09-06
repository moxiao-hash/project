CREATE TABLE artifact_review_previews (
    id VARCHAR(36) PRIMARY KEY,
    artifact_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    runner_execution_id VARCHAR(36) NOT NULL,
    runner_template_type VARCHAR(40) NOT NULL,
    runner_summary VARCHAR(4000) NOT NULL,
    manifest_json TEXT NOT NULL,
    excluded_paths_json TEXT NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    model_name VARCHAR(100),
    error_message VARCHAR(500),
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_artifact_review_preview_artifact
        FOREIGN KEY (artifact_id) REFERENCES roadmap_artifacts (id),
    CONSTRAINT fk_artifact_review_preview_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_artifact_review_preview_runner
        FOREIGN KEY (runner_execution_id) REFERENCES runner_executions (id),
    CONSTRAINT uk_artifact_review_preview_idempotency
        UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_artifact_review_preview_artifact
    ON artifact_review_previews (artifact_id, created_at);
