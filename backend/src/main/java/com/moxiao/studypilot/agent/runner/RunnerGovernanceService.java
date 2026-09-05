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
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class RunnerGovernanceService {

    private final ProjectWorkspaceJpaRepository workspaceRepository;
    private final UserAccountJpaRepository userRepository;
    private final RunnerExecutionJpaRepository runnerRepository;
    private final AgentGovernanceService governanceService;
    private final NotificationService notificationService;
    private final AuditLogJpaRepository auditLogRepository;
    private final IsolatedRunnerExecutor isolatedExecutor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public RunnerGovernanceService(
            ProjectWorkspaceJpaRepository workspaceRepository,
            UserAccountJpaRepository userRepository,
            RunnerExecutionJpaRepository runnerRepository,
            AgentGovernanceService governanceService,
            NotificationService notificationService,
            AuditLogJpaRepository auditLogRepository,
            IsolatedRunnerExecutor isolatedExecutor,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.runnerRepository = runnerRepository;
        this.governanceService = governanceService;
        this.notificationService = notificationService;
        this.auditLogRepository = auditLogRepository;
        this.isolatedExecutor = isolatedExecutor;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RunnerExecutionPreview preview(String ownerId, RunnerExecutionRequest request) {
        ProjectWorkspaceEntity workspace = requireOwnedWorkspace(ownerId, request.workspaceId());
        WorkspaceBinding binding = validateCurrentWorkspace(workspace);
        String targetPattern = normalizeTargetPattern(request.targetPattern());
        RunnerTemplateType template = request.templateType();
        List<String> tokens = template.resolveTokens(targetPattern);
        String explanation = normalizeExplanation(request.explanation(), workspace, template);
        return new RunnerExecutionPreview(
                workspace.getId(), workspace.getName(), binding.canonicalPath(), template,
                template.getDescription(), template.getRiskLevel(), tokens,
                String.join(" ", tokens), template.getDefaultTimeoutSeconds(),
                template.isConfirmationRequired(), explanation);
    }

    public RunnerExecutionResult submit(String ownerId, RunnerExecutionRequest request) {
        CanonicalRequest canonical = canonicalRequest(request);
        Submission submission = transactions.execute(status -> prepareSubmission(ownerId, canonical));
        if (submission == null) {
            throw new IllegalStateException("Runner 执行创建事务未返回结果");
        }
        return submission.execute() ? executeOutsideTransaction(submission.entity()) : response(submission.entity());
    }

    public RunnerExecutionResult confirm(String ownerId, String runnerExecutionId) {
        Submission submission = transactions.execute(status -> claimConfirmation(ownerId, runnerExecutionId));
        if (submission == null) {
            throw new IllegalStateException("Runner 确认事务未返回结果");
        }
        return submission.execute() ? executeOutsideTransaction(submission.entity()) : response(submission.entity());
    }

    public RunnerExecutionResult reject(String ownerId, String runnerExecutionId) {
        RunnerExecutionEntity entity = transactions.execute(status -> {
            RunnerExecutionEntity execution = requireOwnedForUpdate(ownerId, runnerExecutionId);
            if (execution.getStatus() == ExecutionStatus.REJECTED) {
                return execution;
            }
            if (execution.getStatus() != ExecutionStatus.WAITING_CONFIRMATION
                    && execution.getStatus() != ExecutionStatus.WAITING_AUTHORIZATION) {
                throw new ConflictException("当前 Runner 执行不能拒绝");
            }
            governanceService.reject(ownerId, execution.getGovernanceExecutionId());
            execution.reject(Instant.now());
            audit(ownerId, "RUNNER_EXECUTION_REJECTED", execution,
                    "用户拒绝 " + execution.getTemplateType().name() + "，工作区 " + execution.getWorkspaceId());
            return runnerRepository.save(execution);
        });
        if (entity == null) throw new IllegalStateException("Runner 拒绝事务未返回结果");
        return response(entity);
    }

    public RunnerExecutionResult get(String ownerId, String runnerExecutionId) {
        RunnerExecutionEntity entity = transactions.execute(status -> runnerRepository
                .findByIdAndOwnerId(runnerExecutionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Runner 执行不存在")));
        if (entity == null) throw new IllegalStateException("Runner 查询事务未返回结果");
        return response(entity);
    }

    private Submission prepareSubmission(String ownerId, CanonicalRequest request) {
        userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        RunnerExecutionEntity existing = runnerRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(request.requestFingerprint())) {
                throw new ConflictException("Runner 幂等键已用于不同执行请求");
            }
            return new Submission(existing, false);
        }

        ProjectWorkspaceEntity workspace = requireOwnedWorkspace(ownerId, request.workspaceId());
        WorkspaceBinding binding = validateCurrentWorkspace(workspace);
        RunnerTemplateType template = request.templateType();
        String explanation = normalizeExplanation(request.explanation(), workspace, template);
        RiskLevel governanceRisk = template.isConfirmationRequired() ? RiskLevel.HIGH : RiskLevel.LOW;
        AgentExecutionEntity governance = governanceService.createExecution(new CreateAgentExecutionRequest(
                ownerId, "runner:" + sha256(request.idempotencyKey()), ExecutionType.RUNNER_EXECUTION,
                TriggerType.USER_REQUEST, governanceRisk, AgentScope.RUNNER_MANAGEMENT, explanation));
        Instant now = Instant.now();
        RunnerExecutionEntity entity = runnerRepository.save(new RunnerExecutionEntity(
                UUID.randomUUID().toString(), ownerId, workspace.getId(), binding.canonicalPath(),
                binding.identity(), template, request.targetPattern(),
                objectMapper.writeValueAsString(request.commandTokens()), template.getRiskLevel(),
                request.timeoutSeconds(), request.idempotencyKey(), request.requestFingerprint(),
                governance.getId(), governance.getStatus(), now));
        audit(ownerId, "RUNNER_EXECUTION_PREPARED", entity,
                "准备 " + entity.getTemplateType().name() + "，工作区 " + entity.getWorkspaceId()
                        + "，命令 " + String.join(" ", request.commandTokens()));
        if (entity.getStatus() == ExecutionStatus.WAITING_CONFIRMATION
                || entity.getStatus() == ExecutionStatus.WAITING_AUTHORIZATION) {
            notificationService.create(new CreateNotificationRequest(
                    ownerId, NotificationType.AGENT_ACTION_READY,
                    entity.getStatus() == ExecutionStatus.WAITING_CONFIRMATION
                            ? "Runner 执行待确认" : "Runner 执行待授权",
                    template.name() + " @ " + workspace.getName()));
            return new Submission(entity, false);
        }
        if (entity.getStatus() != ExecutionStatus.PENDING) {
            return new Submission(entity, false);
        }
        entity.running(now);
        governanceService.update(governance.getId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null));
        return new Submission(runnerRepository.save(entity), true);
    }

    private Submission claimConfirmation(String ownerId, String runnerExecutionId) {
        RunnerExecutionEntity entity = requireOwnedForUpdate(ownerId, runnerExecutionId);
        if (isTerminal(entity.getStatus()) || entity.getStatus() == ExecutionStatus.RUNNING) {
            return new Submission(entity, false);
        }
        if (entity.getStatus() != ExecutionStatus.WAITING_CONFIRMATION
                && entity.getStatus() != ExecutionStatus.WAITING_AUTHORIZATION) {
            throw new ConflictException("当前 Runner 执行不需要确认");
        }
        validateRecordedWorkspace(ownerId, entity);
        governanceService.confirm(ownerId, entity.getGovernanceExecutionId());
        Instant now = Instant.now();
        entity.running(now);
        governanceService.update(entity.getGovernanceExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null));
        audit(ownerId, "RUNNER_EXECUTION_CONFIRMED", entity,
                "用户确认 " + entity.getTemplateType().name() + "，工作区 " + entity.getWorkspaceId());
        return new Submission(runnerRepository.save(entity), true);
    }

    private RunnerExecutionResult executeOutsideTransaction(RunnerExecutionEntity claimed) {
        RunnerExecutionResult executorResult;
        try {
            executorResult = isolatedExecutor.execute(
                    claimed.getGovernanceExecutionId(), claimed.getWorkspaceId(), claimed.getTemplateType(),
                    claimed.getWorkspacePath(), commandTokens(claimed), claimed.getTimeoutSeconds());
        } catch (RuntimeException exception) {
            executorResult = new RunnerExecutionResult(
                    claimed.getId(), claimed.getGovernanceExecutionId(), claimed.getWorkspaceId(),
                    claimed.getTemplateType(), ExecutionStatus.FAILED.name(), -1, commandTokens(claimed),
                    "", safeSummary(exception.getMessage(), "Runner executor error"), false, 0L, Instant.now());
        }
        RunnerExecutionResult normalized = new RunnerExecutionResult(
                claimed.getId(), claimed.getGovernanceExecutionId(), claimed.getWorkspaceId(),
                claimed.getTemplateType(), executorResult.success()
                ? ExecutionStatus.SUCCEEDED.name() : ExecutionStatus.FAILED.name(),
                executorResult.exitCode(), commandTokens(claimed),
                safeSummary(executorResult.stdoutSummary(), ""), safeSummary(executorResult.stderrSummary(), ""),
                executorResult.success(), executorResult.durationMillis(), executorResult.executedAt());
        RunnerExecutionEntity completed = transactions.execute(status -> finishExecution(claimed, normalized));
        if (completed == null) throw new IllegalStateException("Runner 完成事务未返回结果");
        return response(completed);
    }

    private RunnerExecutionEntity finishExecution(
            RunnerExecutionEntity claimed, RunnerExecutionResult result
    ) {
        RunnerExecutionEntity entity = requireOwnedForUpdate(claimed.getOwnerId(), claimed.getId());
        if (isTerminal(entity.getStatus())) return entity;
        if (entity.getStatus() != ExecutionStatus.RUNNING) {
            throw new ConflictException("Runner 执行状态已变化");
        }
        entity.complete(result, Instant.now());
        governanceService.update(entity.getGovernanceExecutionId(), new UpdateAgentExecutionRequest(
                result.success() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                result.success() ? "Runner 执行已完成" : null,
                result.success() ? null : result.stderrSummary(), null, null, null,
                result.durationMillis(), null));
        audit(entity.getOwnerId(), result.success() ? "RUNNER_EXECUTION_SUCCEEDED" : "RUNNER_EXECUTION_FAILED",
                entity, entity.getTemplateType().name() + " @ " + entity.getWorkspaceId());
        notificationService.create(new CreateNotificationRequest(
                entity.getOwnerId(), result.success()
                ? NotificationType.AGENT_ACTION_COMPLETED : NotificationType.AGENT_FAILED,
                result.success() ? "Runner 执行已完成" : "Runner 执行失败",
                entity.getTemplateType().name() + " @ " + entity.getWorkspaceId()));
        return runnerRepository.save(entity);
    }

    private RunnerExecutionEntity requireOwnedForUpdate(String ownerId, String runnerExecutionId) {
        return runnerRepository.findOwnedForUpdate(runnerExecutionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Runner 执行不存在"));
    }

    private ProjectWorkspaceEntity requireOwnedWorkspace(String ownerId, String workspaceId) {
        return workspaceRepository.findByIdAndOwnerId(workspaceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("工作区不存在或无权限"));
    }

    private void validateRecordedWorkspace(String ownerId, RunnerExecutionEntity execution) {
        ProjectWorkspaceEntity workspace = requireOwnedWorkspace(ownerId, execution.getWorkspaceId());
        WorkspaceBinding current = validateCurrentWorkspace(workspace);
        if (!execution.getWorkspacePath().equals(current.canonicalPath())
                || !execution.getWorkspaceFingerprint().equals(current.identity())) {
            throw new ConflictException("工作区路径或指纹已变化，请重新提交 Runner 执行");
        }
    }

    private WorkspaceBinding validateCurrentWorkspace(ProjectWorkspaceEntity workspace) {
        if (workspace.getRootPath() == null || workspace.getRootPath().isBlank()) {
            throw new ConflictException("工作区目录不能为空");
        }
        try {
            Path configured = Path.of(workspace.getRootPath());
            if (!configured.isAbsolute() || Files.isSymbolicLink(configured)) {
                throw new ConflictException("工作区路径不再有效");
            }
            Path canonical = configured.toRealPath();
            if (!Files.isDirectory(canonical)
                    || !canonical.toString().equals(workspace.getRootPath())
                    || !sha256(canonical.toString()).equals(workspace.getRootPathHash())) {
                throw new ConflictException("工作区路径或指纹已变化，请重新注册");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String identityMaterial = String.join("\u001f",
                    canonical.toString(), String.valueOf(attributes.fileKey()),
                    attributes.creationTime().toString());
            return new WorkspaceBinding(canonical.toString(), sha256(identityMaterial));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ConflictException conflict) throw conflict;
            throw new ConflictException("工作区本地目录不存在或不可访问");
        }
    }

    private String normalizeTargetPattern(String targetPattern) {
        if (targetPattern == null || targetPattern.isBlank()) return null;
        String normalized = targetPattern.trim();
        if (!normalized.matches("^[a-zA-Z0-9_.*-]+$")) {
            throw new IllegalArgumentException("测试目标参数含有非法字符");
        }
        return normalized;
    }

    private String normalizeExplanation(
            String explanation, ProjectWorkspaceEntity workspace, RunnerTemplateType template
    ) {
        return explanation != null && !explanation.isBlank()
                ? explanation.trim()
                : "执行工作区《" + workspace.getName() + "》的 " + template.getDescription();
    }

    private CanonicalRequest canonicalRequest(RunnerExecutionRequest request) {
        String workspaceId = request.workspaceId().trim();
        String idempotencyKey = request.idempotencyKey().trim();
        String targetPattern = normalizeTargetPattern(request.targetPattern());
        String explanation = normalizeNullable(request.explanation());
        RunnerTemplateType template = request.templateType();
        List<String> commandTokens = template.resolveTokens(targetPattern);
        int timeoutSeconds = template.getDefaultTimeoutSeconds();
        String fingerprint = sha256(String.join("\u001f",
                workspaceId, template.name(), normalizeNullable(targetPattern),
                commandTokens.toString(), Integer.toString(timeoutSeconds), explanation));
        return new CanonicalRequest(
                workspaceId, template, targetPattern, explanation, commandTokens,
                timeoutSeconds, idempotencyKey, fingerprint);
    }

    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private List<String> commandTokens(RunnerExecutionEntity entity) {
        JsonNode node = objectMapper.readTree(entity.getCommandTokensJson());
        List<String> tokens = new ArrayList<>();
        node.forEach(item -> tokens.add(item.asText()));
        return List.copyOf(tokens);
    }

    private RunnerExecutionResult response(RunnerExecutionEntity entity) {
        return new RunnerExecutionResult(
                entity.getId(), entity.getGovernanceExecutionId(), entity.getWorkspaceId(),
                entity.getTemplateType(), entity.getStatus().name(),
                entity.getExitCode() == null ? 0 : entity.getExitCode(), commandTokens(entity),
                entity.getStdoutSummary() == null ? "" : entity.getStdoutSummary(),
                entity.getStderrSummary() == null ? "" : entity.getStderrSummary(),
                Boolean.TRUE.equals(entity.getSuccess()),
                entity.getDurationMillis() == null ? 0L : entity.getDurationMillis(),
                entity.getExecutedAt());
    }

    private boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCEEDED
                || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.REJECTED;
    }

    private String safeSummary(String value, String fallback) {
        String safe = value == null || value.isBlank() ? fallback : value;
        return safe.substring(0, Math.min(safe.length(), 2000));
    }

    private void audit(String ownerId, String action, RunnerExecutionEntity entity, String details) {
        auditLogRepository.save(new AuditLogEntity(
                ownerId, action, "RUNNER_EXECUTION", entity.getId(), details, Instant.now()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RunnerExecutionResult submitExecution(String ownerId, RunnerExecutionRequest request) {
        return submit(ownerId, request);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RunnerExecutionResult confirmExecution(String ownerId, String runnerExecutionId) {
        return confirm(ownerId, runnerExecutionId);
    }

    public RunnerExecutionResult rejectExecution(String ownerId, String runnerExecutionId) {
        return reject(ownerId, runnerExecutionId);
    }

    private record Submission(RunnerExecutionEntity entity, boolean execute) { }

    private record WorkspaceBinding(String canonicalPath, String identity) { }

    private record CanonicalRequest(
            String workspaceId,
            RunnerTemplateType templateType,
            String targetPattern,
            String explanation,
            List<String> commandTokens,
            int timeoutSeconds,
            String idempotencyKey,
            String requestFingerprint
    ) { }
}
