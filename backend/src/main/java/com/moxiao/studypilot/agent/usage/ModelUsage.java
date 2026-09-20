package com.moxiao.studypilot.agent.usage;

/** 一次模型调用的原始用量；不包含提示词、用户数据或密钥。 */
public record ModelUsage(
        String modelName,
        long promptTokens,
        long cachedPromptTokens,
        long completionTokens,
        Integer reasoningTokens,
        long latencyMs
) {

    /** 非缓存输入 = 输入总量 - 缓存命中输入。 */
    public long uncachedPromptTokens() {
        return Math.max(0L, promptTokens - cachedPromptTokens);
    }

    /** 总 token = 输入 + 输出；reasoning 是 completion 的子集，不重复相加。 */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
