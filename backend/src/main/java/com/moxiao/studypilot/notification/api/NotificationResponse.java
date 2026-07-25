package com.moxiao.studypilot.notification.api;

import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;

import java.time.Instant;

public record NotificationResponse(
        String id,
        NotificationType type,
        String title,
        String content,
        boolean read,
        Instant createdAt,
        Instant readAt
) {
    public static NotificationResponse from(NotificationEntity entity) {
        return new NotificationResponse(
                entity.getId(),
                entity.getType(),
                entity.getTitle(),
                entity.getContent(),
                entity.isRead(),
                entity.getCreatedAt(),
                entity.getReadAt()
        );
    }
}
