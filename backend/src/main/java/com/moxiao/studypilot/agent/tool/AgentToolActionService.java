package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentToolActionService {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(15);

    private final AgentToolActionJpaRepository repository;
    private final AgentGovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final Map<String, GovernedAgentToolHandler> handlers;
    private final AgentToolBusinessExecutor businessExecutor;
    private final NotificationService notificationService;

    public AgentToolActionService(
            AgentToolActionJpaRepository repository,
            AgentGovernanceService governanceService,
            ObjectMapper objectMapper,
            List<AgentToolHandler> handlers,
            AgentToolBusinessExecutor businessExecutor,
            NotificationService notificationService
    ) {
        this.repository = repository;
        this.governanceService = governanceService;
        this.objectMapper = objectMapper;
        this.businessExecutor = businessExecutor;
        this.notificationService = notificationService;
        this.handlers = handlers.stream()
                .filter(GovernedAgentToolHandler.class::isInstance)
                .map(GovernedAgentToolHandler.class::cast)
                .collect(Collectors.toUnmodifiableMap(
                        handler -> handler.descriptor().name(), Function.identity()));
    }

    @Transactional
    public AgentToolActionResponse prepare(
            GovernedAgentToolHandler handler,
            String ownerId,
            String idempotencyKey,
            JsonNode arguments
    ) {
        AgentToolActionEntity existing = repository
                .findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.getToolName().equals(handler.descriptor().name())
                    || !objectMapper.readTree(existing.getArgumentsJson()).equals(arguments)) {
                throw new ConflictException("幂等键已被其他 Agent 工具操作使用");
            }
            return response(existing);
        }
        AgentToolDescriptor descriptor = handler.descriptor();
        RiskLevel risk = descriptor.riskLevel() == AgentToolRiskLevel.HIGH
                ? RiskLevel.HIGH : RiskLevel.LOW;
        var execution = governanceService.createExecution(new CreateAgentExecutionRequest(
                ownerId,
                "tool-action:" + idempotencyKey,
                handler.executionType(),
                TriggerType.USER_REQUEST,
                risk,
                AgentScope.valueOf(descriptor.requiredScope()),
                handler.summary(arguments)));
        AgentToolActionStatus initialStatus = switch (execution.getStatus()) {
            case WAITING_CONFIRMATION -> AgentToolActionStatus.WAITING_CONFIRMATION;
            case WAITING_AUTHORIZATION -> AgentToolActionStatus.WAITING_AUTHORIZATION;
            case PENDING -> AgentToolActionStatus.READY;
            default -> throw new IllegalStateException("执行记录初始状态异常");
        };
        Instant now = Instant.now();
        AgentToolActionEntity action = repository.save(new AgentToolActionEntity(
                UUID.randomUUID().toString(), ownerId, execution.getId(), idempotencyKey,
                descriptor.name(), descriptor.version(), descriptor.riskLevel(), initialStatus,
                handler.summary(arguments), objectMapper.writeValueAsString(arguments),
                now.plus(CONFIRMATION_TTL), now));
        if (initialStatus == AgentToolActionStatus.READY) {
            return execute(action);
        }
        notificationService.create(new CreateNotificationRequest(
                ownerId,
                NotificationType.AGENT_ACTION_READY,
                "Agent 操作等待确认",
                action.getSummary()));
        return response(action);
    }

    @Transactional
    public AgentToolActionResponse confirm(String ownerId, String actionId) {
        AgentToolActionEntity action = repository.findOwnedForUpdate(actionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具操作不存在"));
        if (action.getStatus() == AgentToolActionStatus.SUCCEEDED) {
            return response(action);
        }
        if (action.getStatus() != AgentToolActionStatus.WAITING_CONFIRMATION
                && action.getStatus() != AgentToolActionStatus.WAITING_AUTHORIZATION) {
            throw new ConflictException("当前 Agent 工具操作不能确认");
        }
        if (Instant.now().isAfter(action.getExpiresAt())) {
            throw new ConflictException("Agent 工具操作确认已过期，请重新发起");
        }
        governanceService.confirm(ownerId, action.getExecutionId());
        action.ready(Instant.now());
        repository.save(action);
        return execute(action);
    }

    @Transactional
    public AgentToolActionResponse reject(String ownerId, String actionId) {
        AgentToolActionEntity action = repository.findOwnedForUpdate(actionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具操作不存在"));
        if (action.getStatus() == AgentToolActionStatus.REJECTED) {
            return response(action);
        }
        if (action.getStatus() != AgentToolActionStatus.WAITING_CONFIRMATION
                && action.getStatus() != AgentToolActionStatus.WAITING_AUTHORIZATION) {
            throw new ConflictException("当前 Agent 工具操作不能拒绝");
        }
        governanceService.reject(ownerId, action.getExecutionId());
        action.reject(Instant.now());
        return response(repository.save(action));
    }

    private AgentToolActionResponse execute(AgentToolActionEntity action) {
        GovernedAgentToolHandler handler = handlers.get(action.getToolName());
        if (handler == null || handler.descriptor().version() != action.getToolVersion()) {
            throw new IllegalStateException("Agent 工具版本不可用: " + action.getToolName());
        }
        action.running(Instant.now());
        repository.save(action);
        governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null));
        try {
            Object result = businessExecutor.execute(handler,
                    new AgentToolContext(action.getOwnerId(), action.getIdempotencyKey()),
                    objectMapper.readTree(action.getArgumentsJson()));
            action.succeed(objectMapper.writeValueAsString(result), Instant.now());
            governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.SUCCEEDED, "工具操作执行成功", null,
                    null, null, null, null, null));
            notificationService.create(new CreateNotificationRequest(
                    action.getOwnerId(), NotificationType.AGENT_ACTION_COMPLETED,
                    "Agent 操作已完成", action.getSummary()));
            return response(repository.save(action));
        } catch (RuntimeException exception) {
            action.fail(safeError(exception), Instant.now());
            repository.save(action);
            governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.FAILED, null, safeError(exception),
                    null, null, null, null, null));
            notificationService.create(new CreateNotificationRequest(
                    action.getOwnerId(), NotificationType.AGENT_FAILED,
                    "Agent 操作失败", safeError(exception)));
            return response(action);
        }
    }

    private AgentToolActionResponse response(AgentToolActionEntity action) {
        return new AgentToolActionResponse(
                action.getId(), action.getExecutionId(), action.getToolName(),
                action.getToolVersion(), action.getRiskLevel(), action.getStatus(),
                action.getSummary(), objectMapper.readTree(action.getArgumentsJson()),
                action.getResultJson() == null ? null : objectMapper.readTree(action.getResultJson()),
                action.getErrorMessage(), action.getExpiresAt());
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Agent 工具执行失败";
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
