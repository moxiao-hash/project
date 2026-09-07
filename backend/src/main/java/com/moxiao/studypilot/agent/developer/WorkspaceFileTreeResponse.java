package com.moxiao.studypilot.agent.developer;

import java.util.List;

public record WorkspaceFileTreeResponse(
        String workspaceId,
        String rootPath,
        List<FileEntry> entries
) {
    public record FileEntry(
            String relativePath,
            String name,
            boolean directory,
            long sizeBytes
    ) {}
}
