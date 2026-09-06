package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

import java.time.Instant;
import java.util.List;

/** Java 向独立 Runner 签发的完整执行授权。 */
public record RunnerSignedEnvelope(
        String protocolVersion,
        String executionId,
        String ownerId,
        String workspaceId,
        RunnerTemplateType templateType,
        AgentToolRiskLevel riskLevel,
        String workspacePath,
        List<String> commandTokens,
        RunnerIsolationMode isolationMode,
        boolean networkDisabled,
        String memoryLimit,
        String cpuLimit,
        int timeoutSeconds,
        Instant issuedAt,
        Instant confirmedAt,
        Instant expiresAt,
        String nonce,
        String signature
) { }
