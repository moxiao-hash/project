package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

import java.util.List;

public record RunnerExecutionPreview(
        String workspaceId,
        String workspaceName,
        String workspacePath,
        RunnerTemplateType templateType,
        String templateDescription,
        AgentToolRiskLevel riskLevel,
        List<String> commandTokens,
        String renderedCommand,
        int timeoutSeconds,
        boolean confirmationRequired,
        String explanation
) {
}
