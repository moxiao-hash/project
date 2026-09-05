package com.moxiao.studypilot.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class IsolatedRunnerExecutor {

    private static final Logger log = LoggerFactory.getLogger(IsolatedRunnerExecutor.class);

    private final RunnerSecurityService securityService;
    private final boolean dockerAvailable;
    private final boolean podmanAvailable;

    public IsolatedRunnerExecutor(
            RunnerSecurityService securityService,
            @Value("${studypilot.runner.force-emulated:false}") boolean forceEmulated
    ) {
        this.securityService = securityService;
        this.dockerAvailable = !forceEmulated && checkBinaryAvailable("docker");
        this.podmanAvailable = !forceEmulated && checkBinaryAvailable("podman");
    }

    public RunnerIsolationMode resolveIsolationMode() {
        if (podmanAvailable) {
            return RunnerIsolationMode.PODMAN;
        } else if (dockerAvailable) {
            return RunnerIsolationMode.DOCKER;
        } else {
            return RunnerIsolationMode.EMULATED_SOCKET;
        }
    }

    public RunnerExecutionResult execute(
            String executionId,
            String workspaceId,
            RunnerTemplateType templateType,
            String workspacePath,
            List<String> commandTokens,
            int timeoutSeconds
    ) {
        RunnerIsolationMode mode = resolveIsolationMode();
        boolean networkDisabled = templateType != RunnerTemplateType.PREPARE_DEPENDENCIES;
        String memoryLimit = "512m";
        String cpuLimit = "1.0";

        // 1. Create signed envelope with nonce and 10min expiry
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                executionId, workspacePath, commandTokens, mode, networkDisabled,
                memoryLimit, cpuLimit, timeoutSeconds
        );

        // 2. Verify envelope integrity & nonce replay protection before execution
        if (!securityService.verifyEnvelope(envelope, Instant.now())) {
            throw new SecurityException("Runner 信封验签失败或 Nonce 重放");
        }

        // 3. Dispatch execution
        long startTime = System.currentTimeMillis();
        Instant executedAt = Instant.now();

        try {
            if (mode == RunnerIsolationMode.DOCKER || mode == RunnerIsolationMode.PODMAN) {
                return executeInContainer(envelope, workspaceId, templateType, mode, startTime, executedAt);
            } else {
                return executeEmulated(envelope, workspaceId, templateType, startTime, executedAt);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            log.error("Runner 安全校验异常: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Runner 执行异常: {}", e.getMessage(), e);
            return new RunnerExecutionResult(
                    executionId,
                    workspaceId,
                    templateType,
                    "FAILED",
                    -1,
                    commandTokens,
                    "",
                    "Runner execution error: " + e.getMessage(),
                    false,
                    duration,
                    executedAt
            );
        }
    }

    private RunnerExecutionResult executeInContainer(
            RunnerSignedEnvelope envelope,
            String workspaceId,
            RunnerTemplateType templateType,
            RunnerIsolationMode mode,
            long startTime,
            Instant executedAt
    ) throws Exception {
        String cli = mode == RunnerIsolationMode.PODMAN ? "podman" : "docker";
        List<String> cliArgs = new ArrayList<>();
        cliArgs.add(cli);
        cliArgs.add("run");
        cliArgs.add("--rm");
        if (envelope.networkDisabled()) {
            cliArgs.add("--network");
            cliArgs.add("none");
        }
        cliArgs.add("-m");
        cliArgs.add(envelope.memoryLimit());
        cliArgs.add("--cpus");
        cliArgs.add(envelope.cpuLimit());
        cliArgs.add("-v");
        cliArgs.add(envelope.workspacePath() + ":/workspace:rw");
        cliArgs.add("-w");
        cliArgs.add("/workspace");
        cliArgs.add("eclipse-temurin:21-jdk-jammy");
        cliArgs.addAll(envelope.commandTokens());

        ProcessBuilder pb = new ProcessBuilder(cliArgs);
        Process process = pb.start();
        boolean finished = process.waitFor(envelope.timeoutSeconds(), TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;
        if (!finished) {
            process.destroyForcibly();
            return new RunnerExecutionResult(
                    envelope.executionId(),
                    workspaceId,
                    templateType,
                    "TIMEOUT",
                    -1,
                    envelope.commandTokens(),
                    "",
                    "Container execution timed out after " + envelope.timeoutSeconds() + " seconds",
                    false,
                    duration,
                    executedAt
            );
        }

        int exitCode = process.exitValue();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        return new RunnerExecutionResult(
                envelope.executionId(),
                workspaceId,
                templateType,
                exitCode == 0 ? "SUCCEEDED" : "FAILED",
                exitCode,
                envelope.commandTokens(),
                stdout.length() > 5000 ? stdout.substring(0, 5000) + "... [truncated]" : stdout,
                stderr.length() > 5000 ? stderr.substring(0, 5000) + "... [truncated]" : stderr,
                exitCode == 0,
                duration,
                executedAt
        );
    }

    private RunnerExecutionResult executeEmulated(
            RunnerSignedEnvelope envelope,
            String workspaceId,
            RunnerTemplateType templateType,
            long startTime,
            Instant executedAt
    ) throws Exception {
        // Emulated mode: simulate container/unix socket dispatch with safe directory boundary
        File workDir = new File(envelope.workspacePath());
        if (!workDir.exists() || !workDir.isDirectory()) {
            throw new IllegalArgumentException("工作区路径不存在: " + envelope.workspacePath());
        }

        // Check symlink escape
        Path realPath = workDir.toPath().toRealPath();
        if (!realPath.equals(workDir.toPath().toAbsolutePath())) {
            throw new SecurityException("禁止在符号链接工作区执行");
        }

        ProcessBuilder pb = new ProcessBuilder(envelope.commandTokens());
        pb.directory(workDir);

        // Environment isolation: restrict environment variables
        pb.environment().clear();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            pb.environment().put("PATH", pathEnv);
        }
        String homeEnv = System.getenv("HOME");
        if (homeEnv != null) {
            pb.environment().put("HOME", homeEnv);
        }

        Process process = pb.start();
        boolean finished = process.waitFor(envelope.timeoutSeconds(), TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        if (!finished) {
            process.destroyForcibly();
            return new RunnerExecutionResult(
                    envelope.executionId(),
                    workspaceId,
                    templateType,
                    "TIMEOUT",
                    -1,
                    envelope.commandTokens(),
                    "",
                    "Execution timed out after " + envelope.timeoutSeconds() + " seconds",
                    false,
                    duration,
                    executedAt
            );
        }

        int exitCode = process.exitValue();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        return new RunnerExecutionResult(
                envelope.executionId(),
                workspaceId,
                templateType,
                exitCode == 0 ? "SUCCEEDED" : "FAILED",
                exitCode,
                envelope.commandTokens(),
                stdout.length() > 5000 ? stdout.substring(0, 5000) + "... [truncated]" : stdout,
                stderr.length() > 5000 ? stderr.substring(0, 5000) + "... [truncated]" : stderr,
                exitCode == 0,
                duration,
                executedAt
        );
    }

    private static boolean checkBinaryAvailable(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
