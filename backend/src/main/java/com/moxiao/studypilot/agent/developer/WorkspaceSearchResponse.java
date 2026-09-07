package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record WorkspaceSearchResponse(
        String workspaceId,
        String query,
        List<MatchEntry> matches
) {
    public record MatchEntry(
            String relativePath,
            int lineNumber,
            String lineContent
    ) {}
}
