CREATE TABLE notifications (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    type VARCHAR(30) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    read_at TIMESTAMP(6),
    CONSTRAINT fk_notifications_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_notifications_owner_created
    ON notifications (owner_id, created_at);
CREATE INDEX idx_notifications_owner_read
    ON notifications (owner_id, is_read);
