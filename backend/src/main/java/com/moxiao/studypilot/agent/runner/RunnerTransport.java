package com.moxiao.studypilot.agent.runner;

@FunctionalInterface
public interface RunnerTransport {
    RunnerExecutionResult execute(
            RunnerSignedEnvelope envelope,
            String workspaceId,
            RunnerTemplateType templateType
    );
}
