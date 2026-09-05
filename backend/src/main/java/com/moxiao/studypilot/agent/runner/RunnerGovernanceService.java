package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RunnerGovernanceService {

    private final ProjectWorkspaceJpaRepository workspaceRepository;
    private final AgentGovernanceService governanceService;
    private final NotificationService notificationService;
    private final AuditLogJpaRepository auditLogRepository;
    private final IsolatedRunnerExecutor isolatedExecutor;

    public RunnerGovernanceService(
            ProjectWorkspaceJpaRepository workspaceRepository,
            AgentGovernanceService governanceService,
            NotificationService notificationService,
            AuditLogJpaRepository auditLogRepository,
            IsolatedRunnerExecutor isolatedExecutor
    ) {
        this.workspaceRepository = workspaceRepository;
        this.governanceService = governanceService;
        this.notificationService = notificationService;
        this.auditLogRepository = auditLogRepository;
        this.isolatedExecutor = isolatedExecutor;
    }

    public RunnerExecutionPreview preview(String ownerId, RunnerExecutionRequest request) {
        ProjectWorkspaceEntity workspace = requireOwnedWorkspace(ownerId, request.workspaceId());
        validateWorkspacePath(workspace.getRootPath());
        validateTargetPattern(request.targetPattern());

        RunnerTemplateType template = request.templateType();
        List<String> tokens = template.resolveTokens(request.targetPattern());
        String renderedCommand = String.join(" ", tokens);

        String explanation = request.explanation() != null && !request.explanation().isBlank()
                ? request.explanation()
                : "执行工作区《" + workspace.getName() + "》的 " + template.getDescription();

        return new RunnerExecutionPreview(
                workspace.getId(),
                workspace.getName(),
                workspace.getRootPath(),
                template,
                template.getDescription(),
                template.getRiskLevel(),
                tokens,
                renderedCommand,
                template.getDefaultTimeoutSeconds(),
                template.isConfirmationRequired(),
                explanation
        );
    }

    @Transactional
    public RunnerExecutionResult submit(String ownerId, RunnerExecutionRequest request) {
        RunnerExecutionPreview preview = preview(ownerId, request);
        RunnerTemplateType template = preview.templateType();

        String idempotencyKey = "runner:" + UUID.randomUUID();
        RiskLevel riskLevel = template.isConfirmationRequired() ? RiskLevel.HIGH : RiskLevel.LOW;

        AgentExecutionEntity execution = governanceService.createExecution(new CreateAgentExecutionRequest(
                ownerId,
                idempotencyKey,
                ExecutionType.RUNNER_EXECUTION,
                TriggerType.USER_REQUEST,
                riskLevel,
                AgentScope.RUNNER_MANAGEMENT,
                preview.explanation()
        ));

        auditLogRepository.save(new AuditLogEntity(
                ownerId,
                "RUNNER_EXECUTION_PREPARED",
                "RUNNER_EXECUTION",
                execution.getId(),
                "准备执行固定模板: " + template.name() + " [" + preview.renderedCommand() + "]",
                Instant.now()
        ));

        if (execution.getStatus() == ExecutionStatus.WAITING_CONFIRMATION
                || execution.getStatus() == ExecutionStatus.WAITING_AUTHORIZATION) {
            notificationService.create(new CreateNotificationRequest(
                    ownerId,
                    NotificationType.AGENT_ACTION_READY,
                    "Runner 执行待确认",
                    "命令需要确认: " + preview.renderedCommand()
            ));
            return new RunnerExecutionResult(
                    execution.getId(),
                    preview.workspaceId(),
                    template,
                    execution.getStatus().name(),
                    0,
                    preview.commandTokens(),
                    "等待用户确认",
                    "",
                    true,
                    0L,
                    Instant.now()
            );
        }

        return executeDirect(ownerId, execution, preview);
    }

    @Transactional
    public RunnerExecutionResult confirm(String ownerId, String executionId) {
        AgentExecutionEntity execution = governanceService.confirm(ownerId, executionId);
        auditLogRepository.save(new AuditLogEntity(
                ownerId,
                "RUNNER_EXECUTION_CONFIRMED",
                "RUNNER_EXECUTION",
                executionId,
                "用户已确认执行 Runner 任务",
                Instant.now()
        ));

        governanceService.update(executionId, new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null
        ));

        ProjectWorkspaceEntity workspace = workspaceRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId)
                .stream().findFirst().orElse(null);
        String wsPath = workspace != null ? workspace.getRootPath() : System.getProperty("java.io.tmpdir");

        RunnerExecutionResult result = isolatedExecutor.execute(
                executionId,
                workspace != null ? workspace.getId() : "workspace-confirmed",
                RunnerTemplateType.PREPARE_DEPENDENCIES,
                wsPath,
                List.of("mvn", "dependency:resolve"),
                300
        );

        governanceService.update(executionId, new UpdateAgentExecutionRequest(
                result.success() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                result.success() ? "Runner 执行已完成" : "Runner 执行失败",
                result.success() ? null : result.stderrSummary(),
                null, null, null,
                result.durationMillis(), null
        ));

        notificationService.create(new CreateNotificationRequest(
                ownerId,
                result.success() ? NotificationType.AGENT_ACTION_COMPLETED : NotificationType.AGENT_FAILED,
                result.success() ? "Runner 执行已完成" : "Runner 执行失败",
                execution.getSummary()
        ));

        return result;
    }

    @Transactional
    public RunnerExecutionResult reject(String ownerId, String executionId) {
        AgentExecutionEntity execution = governanceService.reject(ownerId, executionId);
        auditLogRepository.save(new AuditLogEntity(
                ownerId,
                "RUNNER_EXECUTION_REJECTED",
                "RUNNER_EXECUTION",
                executionId,
                "用户已拒绝执行 Runner 任务",
                Instant.now()
        ));

        return new RunnerExecutionResult(
                execution.getId(),
                "",
                RunnerTemplateType.PREPARE_DEPENDENCIES,
                "REJECTED",
                -1,
                List.of(),
                "用户已拒绝执行",
                "",
                false,
                0L,
                Instant.now()
        );
    }

    private RunnerExecutionResult executeDirect(String ownerId, AgentExecutionEntity execution, RunnerExecutionPreview preview) {
        governanceService.update(execution.getId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null
        ));

        RunnerExecutionResult result = isolatedExecutor.execute(
                execution.getId(),
                preview.workspaceId(),
                preview.templateType(),
                preview.workspacePath(),
                preview.commandTokens(),
                preview.timeoutSeconds()
        );

        governanceService.update(execution.getId(), new UpdateAgentExecutionRequest(
                result.success() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                result.success() ? "执行成功: " + preview.renderedCommand() : "执行失败",
                result.success() ? null : result.stderrSummary(),
                null, null, null,
                result.durationMillis(), null
        ));

        notificationService.create(new CreateNotificationRequest(
                ownerId,
                result.success() ? NotificationType.AGENT_ACTION_COMPLETED : NotificationType.AGENT_FAILED,
                result.success() ? "Runner 检查完成" : "Runner 检查失败",
                preview.renderedCommand()
        ));

        return result;
    }

    private ProjectWorkspaceEntity requireOwnedWorkspace(String ownerId, String workspaceId) {
        return workspaceRepository.findByIdAndOwnerId(workspaceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("工作区不存在或无权限"));
    }

    private void validateWorkspacePath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("工作区目录不能为空");
        }
        if (rootPath.contains("..")) {
            throw new IllegalArgumentException("非法的工作区路径，禁止路径穿越");
        }
        File file = new File(rootPath);
        if (!file.exists() || !file.isDirectory()) {
            throw new IllegalArgumentException("工作区本地目录不存在: " + rootPath);
        }
    }

    private void validateTargetPattern(String targetPattern) {
        if (targetPattern == null || targetPattern.isBlank()) {
            return;
        }
        if (!targetPattern.matches("^[a-zA-Z0-9_.*-]+$")) {
            throw new IllegalArgumentException("测试目标参数含有非法字符");
        }
        if (targetPattern.contains(";") || targetPattern.contains("&") || targetPattern.contains("|") || targetPattern.contains("`") || targetPattern.contains("$")) {
            throw new IllegalArgumentException("禁止命令拼接注入");
        }
    }
    public RunnerExecutionResult submitExecution(String ownerId, RunnerExecutionRequest request) {
        return submit(ownerId, request);
    }

    public RunnerExecutionResult confirmExecution(String ownerId, String executionId) {
        return confirm(ownerId, executionId);
    }

    public RunnerExecutionResult rejectExecution(String ownerId, String executionId) {
        return reject(ownerId, executionId);
    }
}