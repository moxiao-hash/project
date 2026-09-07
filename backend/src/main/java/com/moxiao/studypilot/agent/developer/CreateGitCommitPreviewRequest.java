package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record CreateGitCommitPreviewRequest(List<String> paths, String message) { }
