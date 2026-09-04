package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.WorkspaceStatus;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;

import java.time.Instant;

public record ProjectWorkspaceResponse(
        String id,
        String name,
        String rootPath,
        WorkspaceStatus status,
        Instant createdAt
) {
    public static ProjectWorkspaceResponse from(ProjectWorkspaceEntity entity) {
        return new ProjectWorkspaceResponse(
                entity.getId(), entity.getName(), entity.getRootPath(),
                entity.getStatus(), entity.getCreatedAt());
    }
}
