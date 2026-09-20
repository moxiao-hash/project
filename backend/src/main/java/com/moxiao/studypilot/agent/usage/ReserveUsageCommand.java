package com.moxiao.studypilot.agent.usage;

/** 一次模型调用的预占请求；{@code usageId} 由 AI 侧生成，跨重试稳定。 */
public record ReserveUsageCommand(
        String usageId,
        String ownerId,
        String conversationId,
        String turnId,
        String purpose,
        String provider,
        String modelName
) {
}
