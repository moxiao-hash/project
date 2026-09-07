package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record GitCommitPreview(
        String workspaceId,
        String branch,
        String expectedHead,
        String changeFingerprint,
        List<String> paths,
        String message
) { }
