package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectRoadmapArtifactRequest(
        @NotBlank(message = "拒绝原因不能为空")
        @Size(max = 2000, message = "拒绝原因长度不能超过 2000 字符")
        String reason
) {
}
