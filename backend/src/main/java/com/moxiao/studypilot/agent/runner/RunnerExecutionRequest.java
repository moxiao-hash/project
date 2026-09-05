package com.moxiao.studypilot.agent.runner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RunnerExecutionRequest(
        @NotBlank(message = "工作区 ID 不能为空")
        String workspaceId,

        @NotNull(message = "执行模板类型不能为空")
        RunnerTemplateType templateType,

        String targetPattern,

        String explanation
) {
}
