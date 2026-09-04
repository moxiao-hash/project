CREATE TABLE project_workspaces (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    root_path VARCHAR(1024) NOT NULL,
    root_path_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_project_workspaces_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT uk_project_workspace_root UNIQUE (owner_id, root_path_hash)
);

CREATE TABLE roadmap_artifacts (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    roadmap_node_id VARCHAR(100) NOT NULL,
    roadmap_module_id VARCHAR(100) NOT NULL,
    roadmap_stage_id VARCHAR(80) NOT NULL,
    node_title VARCHAR(180) NOT NULL,
    module_title VARCHAR(180) NOT NULL,
    stage_title VARCHAR(180) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    canonical_path VARCHAR(1024) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    test_evidence VARCHAR(4000) NOT NULL,
    evaluation_mode VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    submission_version INT NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_roadmap_artifacts_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_artifacts_workspace FOREIGN KEY (workspace_id) REFERENCES project_workspaces (id),
    CONSTRAINT fk_roadmap_artifacts_user_roadmap FOREIGN KEY (user_roadmap_id) REFERENCES user_roadmaps (id),
    CONSTRAINT fk_roadmap_artifacts_user_node FOREIGN KEY (user_roadmap_node_id) REFERENCES user_roadmap_nodes (id),
    CONSTRAINT fk_roadmap_artifacts_node FOREIGN KEY (roadmap_node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT fk_roadmap_artifacts_module FOREIGN KEY (roadmap_module_id) REFERENCES roadmap_modules (id),
    CONSTRAINT fk_roadmap_artifacts_stage FOREIGN KEY (roadmap_stage_id) REFERENCES roadmap_stages (id),
    CONSTRAINT uk_roadmap_artifact_idempotency UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uk_roadmap_artifact_version UNIQUE (user_roadmap_node_id, submission_version)
);

CREATE TABLE roadmap_artifact_reviews (
    id VARCHAR(36) PRIMARY KEY,
    artifact_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(20) NOT NULL,
    to_status VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    details VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_roadmap_artifact_reviews_artifact FOREIGN KEY (artifact_id) REFERENCES roadmap_artifacts (id),
    CONSTRAINT fk_roadmap_artifact_reviews_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_project_workspaces_owner ON project_workspaces (owner_id, created_at);
CREATE INDEX idx_roadmap_artifacts_owner_node ON roadmap_artifacts (owner_id, roadmap_node_id, created_at);
CREATE INDEX idx_roadmap_artifact_reviews_artifact ON roadmap_artifact_reviews (artifact_id, created_at);
