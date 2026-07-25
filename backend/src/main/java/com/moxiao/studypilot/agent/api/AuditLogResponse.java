package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        String action,
        String targetType,
        String targetId,
        String details,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLogEntity entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getAction(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}
