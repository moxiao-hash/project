package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record WorkspaceGitStatusResponse(
        String workspaceId,
        boolean gitRepository,
        String branch,
        String currentCommit,
        boolean clean,
        List<String> modifiedFiles,
        List<String> untrackedFiles
) {
}
