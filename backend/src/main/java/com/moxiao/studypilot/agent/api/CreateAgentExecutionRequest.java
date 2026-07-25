package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAgentExecutionRequest(
        @NotBlank String ownerId,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @NotNull ExecutionType executionType,
        @NotNull TriggerType triggerType,
        @NotNull RiskLevel riskLevel,
        @NotNull AgentScope requiredScope,
        @NotBlank @Size(max = 500) String summary
) {
}
