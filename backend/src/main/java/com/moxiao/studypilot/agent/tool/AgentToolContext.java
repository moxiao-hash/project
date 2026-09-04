package com.moxiao.studypilot.agent.tool;

public record AgentToolContext(String ownerId) {
    public AgentToolContext {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少用户身份");
        }
    }
}
