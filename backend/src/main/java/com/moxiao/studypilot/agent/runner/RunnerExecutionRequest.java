package com.moxiao.studypilot.agent.runner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RunnerExecutionRequest(
        @NotBlank(message = "工作区 ID 不能为空")
        String workspaceId,

        @NotNull(message = "执行模板类型不能为空")
        RunnerTemplateType templateType,

        @Size(max = 255, message = "测试目标参数长度不能超过 255")
        String targetPattern,

        @Size(max = 500, message = "执行说明长度不能超过 500")
        String explanation,

        @NotBlank(message = "幂等键不能为空")
        @Size(max = 180, message = "幂等键长度不能超过 180")
        String idempotencyKey
) {
}
