CREATE TABLE web_search_sessions (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    query VARCHAR(500) NOT NULL,
    provider_request_id VARCHAR(100),
    searched_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_web_search_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE web_search_results (
    id VARCHAR(36) PRIMARY KEY,
    search_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(300) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    snippet VARCHAR(2000) NOT NULL,
    score DOUBLE NOT NULL,
    imported_material_id VARCHAR(36),
    CONSTRAINT fk_web_result_search
        FOREIGN KEY (search_id) REFERENCES web_search_sessions (id),
    CONSTRAINT fk_web_result_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_web_result_material
        FOREIGN KEY (imported_material_id) REFERENCES materials (id)
);

CREATE INDEX idx_web_search_owner_time
    ON web_search_sessions (owner_id, searched_at);
CREATE INDEX idx_web_result_search_score
    ON web_search_results (search_id, score);
