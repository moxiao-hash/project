package com.moxiao.studypilot.agent.developer;

import java.time.Instant;

public record ApplyCodePatchResult(
        String workspaceId,
        String targetFile,
        boolean applied,
        String resultSha256,
        String summary,
        Instant appliedAt
) {
}
