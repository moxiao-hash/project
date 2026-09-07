package com.moxiao.studypilot.agent.developer;

public record GitPushRequest(
        String workspaceId,
        String remoteName,
        String branch,
        String expectedHead
) { }
