package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record CodePatchPreview(
        String workspaceId,
        String targetFile,
        String unifiedDiff,
        String expectedSha256,
        String resultSha256,
        boolean conflict,
        String conflictReason,
        List<String> affectedLines,
        boolean safeToApply
) {
}
