package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectWorkspaceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 1024) String rootPath
) { }
