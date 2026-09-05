package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

import java.util.List;

public enum RunnerTemplateType {
    MAVEN_TEST(
            "Maven 单元测试",
            AgentToolRiskLevel.LOW,
            List.of("mvn", "test"),
            60,
            false
    ),
    MAVEN_COMPILE(
            "Maven 编译与结构检查",
            AgentToolRiskLevel.LOW,
            List.of("mvn", "test-compile"),
            60,
            false
    ),
    NPM_TEST(
            "NPM 前端测试",
            AgentToolRiskLevel.LOW,
            List.of("npm", "test"),
            60,
            false
    ),
    PYTEST(
            "Python Pytest 测试",
            AgentToolRiskLevel.LOW,
            List.of("pytest"),
            60,
            false
    ),
    PREPARE_DEPENDENCIES(
            "准备本地构建依赖",
            AgentToolRiskLevel.HIGH,
            List.of("mvn", "dependency:resolve"),
            180,
            true
    );

    private final String description;
    private final AgentToolRiskLevel riskLevel;
    private final List<String> commandTokens;
    private final int defaultTimeoutSeconds;
    private final boolean confirmationRequired;

    RunnerTemplateType(
            String description,
            AgentToolRiskLevel riskLevel,
            List<String> commandTokens,
            int defaultTimeoutSeconds,
            boolean confirmationRequired
    ) {
        this.description = description;
        this.riskLevel = riskLevel;
        this.commandTokens = commandTokens;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.confirmationRequired = confirmationRequired;
    }

    public String getDescription() {
        return description;
    }

    public AgentToolRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public List<String> getCommandTokens() {
        return commandTokens;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public boolean isConfirmationRequired() {
        return confirmationRequired;
    }
    public List<String> resolveTokens(String targetPattern) {
        if (targetPattern == null || targetPattern.isBlank()) {
            return commandTokens;
        }
        java.util.ArrayList<String> resolved = new java.util.ArrayList<>(commandTokens);
        if (this == MAVEN_TEST) {
            resolved.add("-Dtest=" + targetPattern.trim());
        } else if (this == PYTEST) {
            resolved.add("-k");
            resolved.add(targetPattern.trim());
        }
        return java.util.Collections.unmodifiableList(resolved);
    }
}