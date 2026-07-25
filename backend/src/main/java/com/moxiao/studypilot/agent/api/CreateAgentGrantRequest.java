package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.AgentScope;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Set;

public record CreateAgentGrantRequest(
        @NotEmpty Set<AgentScope> scopes,
        @NotNull @Future Instant expiresAt
) {
}
