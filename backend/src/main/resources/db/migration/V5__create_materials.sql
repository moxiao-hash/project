CREATE TABLE materials (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(180) NOT NULL,
    material_type VARCHAR(30) NOT NULL,
    category VARCHAR(30) NOT NULL,
    privacy_level VARCHAR(20) NOT NULL,
    source_url VARCHAR(2048),
    processing_status VARCHAR(20) NOT NULL,
    summary TEXT,
    content_reference VARCHAR(500),
    failure_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_materials_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE material_tags (
    material_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (material_id, position),
    CONSTRAINT fk_material_tags_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE TABLE material_knowledge_points (
    material_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    knowledge_point VARCHAR(180) NOT NULL,
    PRIMARY KEY (material_id, position),
    CONSTRAINT fk_material_knowledge_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE INDEX idx_materials_owner_created ON materials (owner_id, created_at);
