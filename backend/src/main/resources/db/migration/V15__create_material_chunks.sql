CREATE TABLE material_chunks (
    id VARCHAR(36) PRIMARY KEY,
    material_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    text TEXT NOT NULL,
    locator VARCHAR(255) NOT NULL,
    CONSTRAINT uk_material_chunk_position UNIQUE (material_id, position),
    CONSTRAINT fk_material_chunk_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE TABLE material_processing_warnings (
    material_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    warning VARCHAR(500) NOT NULL,
    PRIMARY KEY (material_id, position),
    CONSTRAINT fk_material_warning_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE INDEX idx_material_chunks_material ON material_chunks (material_id, position);
