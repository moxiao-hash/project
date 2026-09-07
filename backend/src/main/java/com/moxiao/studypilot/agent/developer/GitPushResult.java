package com.moxiao.studypilot.agent.developer;

import java.time.Instant;

public record GitPushResult(
        String workspaceId,
        String remoteName,
        String branch,
        String pushedCommit,
        Instant pushedAt
) { }
