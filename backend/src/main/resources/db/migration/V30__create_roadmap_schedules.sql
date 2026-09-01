CREATE TABLE roadmap_schedule_states (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    time_zone VARCHAR(50) NOT NULL,
    daily_capacity_minutes INT NOT NULL,
    weekends_enabled BOOLEAN NOT NULL,
    refreshed_at TIMESTAMP(6) NOT NULL,
    refresh_requested_at TIMESTAMP(6),
    CONSTRAINT fk_roadmap_schedule_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_schedule_enrollment_scope
        FOREIGN KEY (user_roadmap_id, owner_id) REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT uk_roadmap_schedule_enrollment UNIQUE (owner_id, user_roadmap_id),
    CONSTRAINT uk_roadmap_schedule_scope UNIQUE (id, owner_id, user_roadmap_id),
    CONSTRAINT ck_roadmap_schedule_capacity CHECK (daily_capacity_minutes BETWEEN 15 AND 1440)
);

CREATE TABLE roadmap_schedule_items (
    id VARCHAR(36) PRIMARY KEY,
    schedule_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    scheduled_date DATE NOT NULL,
    planned_minutes INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_roadmap_schedule_item_scope
        FOREIGN KEY (schedule_id, owner_id, user_roadmap_id)
        REFERENCES roadmap_schedule_states (id, owner_id, user_roadmap_id),
    CONSTRAINT fk_roadmap_schedule_item_node_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id, user_roadmap_id, node_id)
        REFERENCES user_roadmap_nodes (id, owner_id, user_roadmap_id, node_id),
    CONSTRAINT fk_roadmap_schedule_item_catalog_node
        FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT uk_roadmap_schedule_item_node UNIQUE (user_roadmap_id, user_roadmap_node_id),
    CONSTRAINT ck_roadmap_schedule_item_minutes CHECK (planned_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_roadmap_schedule_item_status
        CHECK (status IN ('PLANNED', 'STARTED', 'COMPLETED'))
);

CREATE INDEX idx_roadmap_schedule_item_window
    ON roadmap_schedule_items (owner_id, scheduled_date, status);
