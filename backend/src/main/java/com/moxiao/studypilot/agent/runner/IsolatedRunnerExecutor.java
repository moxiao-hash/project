package com.moxiao.studypilot.agent.runner;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

/**
 * Java 侧 Runner 门面，只签发信封并调用独立 Local Runner。
 *
 * <p>此类刻意不包含 {@code ProcessBuilder} 或容器 CLI，防止 Runner 不可用时
 * 意外退回 Spring Boot 宿主进程执行命令。</p>
 */
@Component
public class IsolatedRunnerExecutor {

    private static final String MEMORY_LIMIT = "512m";
    private static final String CPU_LIMIT = "1.0";

    private final RunnerSecurityService securityService;
    private final RunnerTransport transport;

    public IsolatedRunnerExecutor(
            RunnerSecurityService securityService,
            RunnerTransport transport
    ) {
        this.securityService = securityService;
        this.transport = transport;
    }

    public RunnerIsolationMode resolveIsolationMode() {
        return RunnerIsolationMode.LOCAL_RUNNER;
    }

    public RunnerExecutionResult execute(
            String executionId,
            String ownerId,
            String workspaceId,
            RunnerTemplateType templateType,
            AgentToolRiskLevel riskLevel,
            Instant confirmedAt,
            String workspacePath,
            List<String> commandTokens,
            int timeoutSeconds
    ) {
        String canonicalWorkspace = canonicalWorkspace(workspacePath);
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                executionId,
                ownerId,
                workspaceId,
                templateType,
                riskLevel,
                canonicalWorkspace,
                commandTokens,
                RunnerIsolationMode.LOCAL_RUNNER,
                templateType != RunnerTemplateType.PREPARE_DEPENDENCIES,
                MEMORY_LIMIT,
                CPU_LIMIT,
                timeoutSeconds,
                confirmedAt
        );
        return transport.execute(envelope, workspaceId, templateType);
    }

    private String canonicalWorkspace(String workspacePath) {
        try {
            Path configured = Path.of(workspacePath);
            if (!configured.isAbsolute() || Files.isSymbolicLink(configured)) {
                throw new SecurityException("禁止在符号链接或相对工作区执行");
            }
            Path canonical = configured.toRealPath();
            if (!Files.isDirectory(canonical) || !canonical.equals(configured.normalize())) {
                throw new SecurityException("工作区路径不是规范目录");
            }
            return canonical.toString();
        } catch (IOException exception) {
            throw new IllegalArgumentException("工作区路径不存在或不可访问", exception);
        }
    }
}
