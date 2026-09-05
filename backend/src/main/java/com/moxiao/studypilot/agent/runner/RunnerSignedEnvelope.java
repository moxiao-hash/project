package com.moxiao.studypilot.agent.runner;

import java.time.Instant;
import java.util.List;

public record RunnerSignedEnvelope(
        String protocolVersion,
        String executionId,
        String workspacePath,
        List<String> commandTokens,
        RunnerIsolationMode isolationMode,
        boolean networkDisabled,
        String memoryLimit,
        String cpuLimit,
        int timeoutSeconds,
        Instant expiresAt,
        String nonce,
        String signature
) {
}
