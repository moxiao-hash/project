package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.infrastructure.AgentGrantEntity;

import java.time.Instant;
import java.util.Set;

public record AgentGrantResponse(
        String id,
        Set<AgentScope> scopes,
        Instant expiresAt,
        boolean active
) {
    public static AgentGrantResponse from(AgentGrantEntity entity) {
        return new AgentGrantResponse(
                entity.getId(),
                entity.getScopes(),
                entity.getExpiresAt(),
                entity.isActiveAt(Instant.now())
        );
    }
}
