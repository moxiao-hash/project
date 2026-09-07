package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record GitCommitRequest(
        String workspaceId,
        List<String> paths,
        String message,
        String expectedHead,
        String changeFingerprint
) { }
