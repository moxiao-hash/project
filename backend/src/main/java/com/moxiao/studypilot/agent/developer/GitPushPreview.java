package com.moxiao.studypilot.agent.developer;

public record GitPushPreview(
        String workspaceId,
        String remoteName,
        String branch,
        String expectedHead,
        int aheadCount
) { }
