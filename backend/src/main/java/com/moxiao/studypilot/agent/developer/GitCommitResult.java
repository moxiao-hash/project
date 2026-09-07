package com.moxiao.studypilot.agent.developer;

import java.time.Instant;
import java.util.List;

public record GitCommitResult(
        String workspaceId,
        String commitId,
        String message,
        List<String> paths,
        Instant committedAt
) { }
