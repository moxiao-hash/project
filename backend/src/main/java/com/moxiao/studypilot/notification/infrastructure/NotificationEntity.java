package com.moxiao.studypilot.notification.infrastructure;

import com.moxiao.studypilot.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(
            String id,
            String ownerId,
            NotificationType type,
            String title,
            String content,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
    }

    public void markRead(Instant now) {
        read = true;
        readAt = now;
    }

    public String getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
