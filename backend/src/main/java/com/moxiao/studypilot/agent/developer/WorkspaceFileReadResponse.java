package com.moxiao.studypilot.agent.developer;

public record WorkspaceFileReadResponse(
        String workspaceId,
        String relativePath,
        String content,
        long sizeBytes,
        boolean truncated
) {
}
