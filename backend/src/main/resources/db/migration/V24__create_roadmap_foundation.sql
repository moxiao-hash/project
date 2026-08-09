CREATE TABLE roadmap_templates (
    id VARCHAR(36) PRIMARY KEY,
    roadmap_code VARCHAR(80) NOT NULL,
    template_version INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_roadmap_template_version UNIQUE (roadmap_code, template_version)
);

CREATE TABLE roadmap_stages (
    id VARCHAR(80) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    stage_code VARCHAR(80) NOT NULL,
    stage_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    graduation_project_title VARCHAR(240) NOT NULL,
    CONSTRAINT fk_roadmap_stages_template FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_roadmap_stage_template_scope UNIQUE (id, template_id),
    CONSTRAINT uk_roadmap_stage_code UNIQUE (template_id, stage_code),
    CONSTRAINT uk_roadmap_stage_order UNIQUE (template_id, stage_order)
);

CREATE TABLE roadmap_nodes (
    id VARCHAR(100) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    stage_id VARCHAR(80) NOT NULL,
    node_code VARCHAR(100) NOT NULL,
    node_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    objectives_json LONGTEXT NOT NULL,
    high_frequency_json LONGTEXT NOT NULL,
    common_mistakes_json LONGTEXT NOT NULL,
    search_keywords_json LONGTEXT NOT NULL,
    artifact_requirement_json LONGTEXT NOT NULL,
    quiz_blueprint_json LONGTEXT NOT NULL,
    estimated_minutes INT NOT NULL,
    practice_minutes INT NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    required_node BOOLEAN NOT NULL,
    CONSTRAINT fk_roadmap_nodes_template FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_nodes_stage_scope FOREIGN KEY (stage_id, template_id)
        REFERENCES roadmap_stages (id, template_id),
    CONSTRAINT uk_roadmap_node_template_scope UNIQUE (id, template_id),
    CONSTRAINT uk_roadmap_node_code UNIQUE (template_id, node_code),
    CONSTRAINT uk_roadmap_node_order UNIQUE (stage_id, node_order)
);

CREATE TABLE roadmap_node_prerequisites (
    id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    prerequisite_node_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_roadmap_prerequisites_template FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_prerequisites_node_scope FOREIGN KEY (node_id, template_id)
        REFERENCES roadmap_nodes (id, template_id),
    CONSTRAINT fk_roadmap_prerequisites_required_scope
        FOREIGN KEY (prerequisite_node_id, template_id)
        REFERENCES roadmap_nodes (id, template_id),
    CONSTRAINT uk_roadmap_node_prerequisite UNIQUE (node_id, prerequisite_node_id)
);

CREATE TABLE user_roadmaps (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot VARCHAR(20),
    enrolled_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_roadmaps_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_user_roadmaps_template FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_user_roadmap_owner_scope UNIQUE (id, owner_id),
    CONSTRAINT uk_user_roadmap_template_scope UNIQUE (id, template_id),
    CONSTRAINT uk_user_roadmap_template UNIQUE (owner_id, template_id),
    CONSTRAINT uk_user_roadmap_active_slot UNIQUE (owner_id, active_slot),
    CONSTRAINT ck_user_roadmap_active_slot CHECK (
        (status = 'ACTIVE' AND active_slot IS NOT NULL AND active_slot = 'CURRENT')
        OR ((status = 'SUPERSEDED' OR status = 'ARCHIVED') AND active_slot IS NULL)
    )
);

CREATE TABLE user_roadmap_nodes (
    id VARCHAR(36) PRIMARY KEY,
    user_roadmap_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    availability_status VARCHAR(20) NOT NULL,
    learning_status VARCHAR(20) NOT NULL,
    check_in_status VARCHAR(20) NOT NULL,
    quiz_status VARCHAR(30) NOT NULL,
    artifact_status VARCHAR(20) NOT NULL,
    completion_status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_roadmap_nodes_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_user_roadmap_nodes_owner_scope FOREIGN KEY (user_roadmap_id, owner_id)
        REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_user_roadmap_nodes_template_scope FOREIGN KEY (user_roadmap_id, template_id)
        REFERENCES user_roadmaps (id, template_id),
    CONSTRAINT fk_user_roadmap_nodes_node_scope FOREIGN KEY (node_id, template_id)
        REFERENCES roadmap_nodes (id, template_id),
    CONSTRAINT uk_user_roadmap_node_owner_scope UNIQUE (id, owner_id),
    CONSTRAINT uk_user_roadmap_node UNIQUE (user_roadmap_id, node_id)
);

CREATE TABLE roadmap_upgrades (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    target_template_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    diff_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_roadmap_upgrades_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_upgrades_user_scope FOREIGN KEY (user_roadmap_id, owner_id)
        REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_roadmap_upgrades_target_template FOREIGN KEY (target_template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT uk_roadmap_upgrade_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE legacy_lesson_roadmap_mappings (
    lesson_id VARCHAR(80) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (lesson_id, template_id),
    CONSTRAINT fk_legacy_mapping_lesson FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT fk_legacy_mapping_template FOREIGN KEY (template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_legacy_mapping_node_scope FOREIGN KEY (node_id, template_id)
        REFERENCES roadmap_nodes (id, template_id)
);

CREATE TABLE legacy_learning_evidence (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    lesson_id VARCHAR(80) NOT NULL,
    original_status VARCHAR(20) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    migration_version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_legacy_evidence_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_legacy_evidence_user_node_scope FOREIGN KEY (user_roadmap_node_id, owner_id)
        REFERENCES user_roadmap_nodes (id, owner_id),
    CONSTRAINT fk_legacy_evidence_lesson FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT uk_legacy_evidence_migration UNIQUE (owner_id, lesson_id, migration_version)
);

CREATE INDEX idx_user_roadmaps_owner_status ON user_roadmaps (owner_id, status);
CREATE INDEX idx_user_roadmap_nodes_completion ON user_roadmap_nodes (user_roadmap_id, completion_status);
