package com.moxiao.studypilot.agent.usage;

/**
 * 一次模型调用的预占请求；{@code usageId} 由 AI 侧生成，跨重试稳定。
 *
 * <p>{@code inputTokensUpperBound} 是当前请求输入 token 的保守上界（AI 侧按字节上界
 * 计算），用于把输入成本计入预占；费用上限生效时它是必需字段。</p>
 */
public record ReserveUsageCommand(
        String usageId,
        String ownerId,
        String conversationId,
        String turnId,
        String purpose,
        String provider,
        String modelName,
        Integer inputTokensUpperBound
) {
}
