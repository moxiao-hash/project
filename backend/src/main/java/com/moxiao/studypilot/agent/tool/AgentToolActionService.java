package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final UserAccountJpaRepository userRepository;
    private final AgentGovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final Map<String, GovernedAgentToolHandler> handlers;
    private final AgentToolBusinessExecutor businessExecutor;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public AgentToolActionService(
            AgentToolActionJpaRepository repository,
            UserAccountJpaRepository userRepository,
            AgentGovernanceService governanceService,
            ObjectMapper objectMapper,
            List<AgentToolHandler> handlers,
            AgentToolBusinessExecutor businessExecutor,
            NotificationService notificationService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.governanceService = governanceService;
        this.objectMapper = objectMapper;
        this.businessExecutor = businessExecutor;
        this.notificationService = notificationService;
        this.handlers = handlers.stream()
                .filter(GovernedAgentToolHandler.class::isInstance)
                .map(GovernedAgentToolHandler.class::cast)
                .collect(Collectors.toUnmodifiableMap(
                        handler -> handler.descriptor().name(), Function.identity()));
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentToolActionResponse prepare(
            GovernedAgentToolHandler handler,
            String ownerId,
            String idempotencyKey,
            JsonNode arguments
    ) {
        ActionClaim claim = requiresNew.execute(status ->
                prepareAndClaim(handler, ownerId, idempotencyKey, arguments));
        if (claim == null) throw new IllegalStateException("Agent 工具准备事务未返回结果");
        return claim.execute() ? executeClaim(claim.actionId()) : claim.response();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentToolActionResponse confirm(String ownerId, String actionId) {
        ActionClaim claim = requiresNew.execute(status -> claimConfirmation(ownerId, actionId));
        if (claim == null) throw new IllegalStateException("Agent 工具确认事务未返回结果");
        return claim.execute() ? executeClaim(claim.actionId()) : claim.response();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentToolActionResponse reject(String ownerId, String actionId) {
        AgentToolActionResponse response = requiresNew.execute(status -> {
            AgentToolActionEntity action = requireOwnedForUpdate(ownerId, actionId);
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
        });
        if (response == null) throw new IllegalStateException("Agent 工具拒绝事务未返回结果");
        return response;
    }

    private ActionClaim prepareAndClaim(
            GovernedAgentToolHandler handler,
            String ownerId,
            String idempotencyKey,
            JsonNode arguments
    ) {
        userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        AgentToolActionEntity existing = repository
                .findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.getToolName().equals(handler.descriptor().name())
                    || !objectMapper.readTree(existing.getArgumentsJson()).equals(arguments)) {
                throw new ConflictException("幂等键已被其他 Agent 工具操作使用");
            }
            return new ActionClaim(existing.getId(), false, response(existing));
        }
        AgentToolDescriptor descriptor = handler.descriptor();
        RiskLevel risk = descriptor.riskLevel() == AgentToolRiskLevel.HIGH
                ? RiskLevel.HIGH : RiskLevel.LOW;
        var execution = governanceService.createExecution(new CreateAgentExecutionRequest(
                ownerId, "tool-action:" + idempotencyKey, handler.executionType(),
                TriggerType.USER_REQUEST, risk, AgentScope.valueOf(descriptor.requiredScope()),
                handler.summary(arguments)));
        AgentToolActionStatus initialStatus = switch (execution.getStatus()) {
            case WAITING_CONFIRMATION -> AgentToolActionStatus.WAITING_CONFIRMATION;
            case WAITING_AUTHORIZATION -> AgentToolActionStatus.WAITING_AUTHORIZATION;
            case PENDING -> AgentToolActionStatus.READY;
            default -> throw new IllegalStateException("执行记录初始状态异常");
        };
        Instant now = Instant.now();
        AgentToolActionEntity action = new AgentToolActionEntity(
                UUID.randomUUID().toString(), ownerId, execution.getId(), idempotencyKey,
                descriptor.name(), descriptor.version(), descriptor.riskLevel(), initialStatus,
                handler.summary(arguments), objectMapper.writeValueAsString(arguments),
                now.plus(CONFIRMATION_TTL), now);
        if (initialStatus == AgentToolActionStatus.READY) {
            action.running(now);
            repository.save(action);
            governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.RUNNING, null, null, null, null, null, null, null));
            return new ActionClaim(action.getId(), true, null);
        }
        repository.save(action);
        notificationService.create(new CreateNotificationRequest(
                ownerId, NotificationType.AGENT_ACTION_READY,
                "Agent 操作等待确认", action.getSummary()));
        return new ActionClaim(action.getId(), false, response(action));
    }

    private ActionClaim claimConfirmation(String ownerId, String actionId) {
        AgentToolActionEntity action = requireOwnedForUpdate(ownerId, actionId);
        if (action.getStatus() == AgentToolActionStatus.SUCCEEDED
                || action.getStatus() == AgentToolActionStatus.FAILED
                || action.getStatus() == AgentToolActionStatus.RUNNING) {
            return new ActionClaim(action.getId(), false, response(action));
        }
        if (action.getStatus() != AgentToolActionStatus.WAITING_CONFIRMATION
                && action.getStatus() != AgentToolActionStatus.WAITING_AUTHORIZATION) {
            throw new ConflictException("当前 Agent 工具操作不能确认");
        }
        if (Instant.now().isAfter(action.getExpiresAt())) {
            throw new ConflictException("Agent 工具操作确认已过期，请重新发起");
        }
        governanceService.confirm(ownerId, action.getExecutionId());
        Instant now = Instant.now();
        action.running(now);
        repository.save(action);
        governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.RUNNING, null, null, null, null, null, null, null));
        return new ActionClaim(action.getId(), true, null);
    }

    private AgentToolActionResponse executeClaim(String actionId) {
        AgentToolActionEntity snapshot = requiresNew.execute(status -> repository.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具操作不存在")));
        if (snapshot == null) throw new IllegalStateException("Agent 工具执行记录不存在");
        try {
            GovernedAgentToolHandler handler = handlers.get(snapshot.getToolName());
            if (handler == null || handler.descriptor().version() != snapshot.getToolVersion()) {
                throw new IllegalStateException("Agent 工具版本不可用: " + snapshot.getToolName());
            }
            Object result = businessExecutor.execute(handler,
                    new AgentToolContext(snapshot.getOwnerId(), snapshot.getIdempotencyKey()),
                    objectMapper.readTree(snapshot.getArgumentsJson()));
            return finalizeSuccess(snapshot.getOwnerId(), actionId, result);
        } catch (RuntimeException exception) {
            return finalizeFailure(snapshot.getOwnerId(), actionId, exception);
        }
    }

    private AgentToolActionResponse finalizeSuccess(String ownerId, String actionId, Object result) {
        AgentToolActionResponse response = requiresNew.execute(status -> {
            AgentToolActionEntity action = requireOwnedForUpdate(ownerId, actionId);
            if (action.getStatus() != AgentToolActionStatus.RUNNING) return response(action);
            action.succeed(objectMapper.writeValueAsString(result), Instant.now());
            governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.SUCCEEDED, "工具操作执行成功", null,
                    null, null, null, null, null));
            notificationService.create(new CreateNotificationRequest(
                    ownerId, NotificationType.AGENT_ACTION_COMPLETED,
                    "Agent 操作已完成", action.getSummary()));
            return response(repository.save(action));
        });
        if (response == null) throw new IllegalStateException("Agent 工具完成事务未返回结果");
        return response;
    }

    private AgentToolActionResponse finalizeFailure(
            String ownerId, String actionId, RuntimeException exception
    ) {
        AgentToolActionResponse response = requiresNew.execute(status -> {
            AgentToolActionEntity action = requireOwnedForUpdate(ownerId, actionId);
            if (action.getStatus() != AgentToolActionStatus.RUNNING) return response(action);
            String error = safeError(exception);
            action.fail(error, Instant.now());
            governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.FAILED, null, error,
                    null, null, null, null, null));
            notificationService.create(new CreateNotificationRequest(
                    ownerId, NotificationType.AGENT_FAILED,
                    "Agent 操作失败", error));
            return response(repository.save(action));
        });
        if (response == null) throw new IllegalStateException("Agent 工具失败事务未返回结果");
        return response;
    }

    private AgentToolActionEntity requireOwnedForUpdate(String ownerId, String actionId) {
        return repository.findOwnedForUpdate(actionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具操作不存在"));
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

    private record ActionClaim(
            String actionId, boolean execute, AgentToolActionResponse response
    ) { }
}
