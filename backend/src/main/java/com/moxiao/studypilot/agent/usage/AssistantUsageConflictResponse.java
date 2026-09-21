package com.moxiao.studypilot.agent.usage;

/** 跨 owner 冲突的稳定机器可读回执；{@code code} 只取固定常量值。 */
public record AssistantUsageConflictResponse(String code, String message) {

    public static AssistantUsageConflictResponse from(AssistantUsageOwnerConflictException conflict) {
        return new AssistantUsageConflictResponse(conflict.code(), conflict.getMessage());
    }
}
