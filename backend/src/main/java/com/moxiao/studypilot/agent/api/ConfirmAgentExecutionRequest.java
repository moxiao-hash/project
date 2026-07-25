package com.moxiao.studypilot.agent.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Python AI 服务确认一次高风险 Agent 执行时使用的内部契约。
 *
 * <p>ownerId 仍然由 Java 后端校验，而不是只相信 executionId。这样即使内部调用方
 * 错传了执行编号，也不能确认到其他用户的执行记录。</p>
 */
public record ConfirmAgentExecutionRequest(
        @NotBlank String ownerId
) {
}
