CREATE TABLE roadmap_modules (
    id VARCHAR(100) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    stage_id VARCHAR(80) NOT NULL,
    module_code VARCHAR(100) NOT NULL,
    module_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    CONSTRAINT fk_roadmap_modules_template FOREIGN KEY (template_id)
        REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_modules_stage_scope FOREIGN KEY (stage_id, template_id)
        REFERENCES roadmap_stages (id, template_id),
    CONSTRAINT uk_roadmap_module_template_scope UNIQUE (id, template_id),
    CONSTRAINT uk_roadmap_module_code UNIQUE (template_id, module_code),
    CONSTRAINT uk_roadmap_module_order UNIQUE (stage_id, module_order)
);

ALTER TABLE roadmap_nodes ADD COLUMN module_id VARCHAR(100);

ALTER TABLE roadmap_nodes
    ADD CONSTRAINT fk_roadmap_nodes_module_scope FOREIGN KEY (module_id, template_id)
        REFERENCES roadmap_modules (id, template_id);
