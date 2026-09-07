package com.moxiao.studypilot.agent.developer;

public record WorkspaceGitTextResponse(
        String workspaceId,
        String content,
        boolean truncated
) {
}
