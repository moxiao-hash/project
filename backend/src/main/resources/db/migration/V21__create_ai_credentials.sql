CREATE TABLE ai_credentials (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_credentials_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT uk_ai_credentials_owner_provider UNIQUE (owner_id, provider)
);

CREATE INDEX idx_ai_credentials_owner ON ai_credentials (owner_id);
