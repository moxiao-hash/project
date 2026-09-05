package com.moxiao.studypilot.agent.runner;

import java.time.Instant;
import java.util.List;

public record RunnerExecutionResult(
        String executionId,
        String governanceExecutionId,
        String workspaceId,
        RunnerTemplateType templateType,
        String status,
        int exitCode,
        List<String> commandTokens,
        String stdoutSummary,
        String stderrSummary,
        boolean success,
        long durationMillis,
        Instant executedAt
) {
    public RunnerExecutionResult(
            String executionId, String workspaceId, RunnerTemplateType templateType,
            String status, int exitCode, List<String> commandTokens,
            String stdoutSummary, String stderrSummary, boolean success,
            long durationMillis, Instant executedAt
    ) {
        this(executionId, null, workspaceId, templateType, status, exitCode,
                commandTokens, stdoutSummary, stderrSummary, success, durationMillis, executedAt);
    }
}
