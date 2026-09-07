package com.moxiao.studypilot.agent.developer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApplyCodePatchRequest(
        @NotBlank(message = "工作区 ID 不能为空")
        String workspaceId,

        @NotBlank(message = "目标文件相对路径不能为空")
        @Size(max = 1024, message = "目标文件相对路径不能超过 1024 字符")
        String targetFile,

        @NotBlank(message = "Unified Diff 补丁内容不能为空")
        @Size(max = 20000, message = "补丁内容不能超过 20000 字符")
        String unifiedDiff,

        @NotBlank(message = "预期文件摘要不能为空")
        @Pattern(regexp = "[0-9a-fA-F]{64}", message = "预期文件摘要必须是 SHA-256")
        String expectedSha256,

        @Size(max = 500, message = "修改说明不能超过 500 字符")
        String explanation
) {
}
