package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.api.CreateAgentGrantRequest;
import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.agent.infrastructure.AgentGrantEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentGrantJpaRepository;
import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AgentGovernanceService {

    private final UserAccountJpaRepository userRepository;
    private final AgentGrantJpaRepository grantRepository;
    private final AgentExecutionJpaRepository executionRepository;
    private final AuditLogJpaRepository auditRepository;

    public AgentGovernanceService(
            UserAccountJpaRepository userRepository,
            AgentGrantJpaRepository grantRepository,
            AgentExecutionJpaRepository executionRepository,
            AuditLogJpaRepository auditRepository
    ) {
        this.userRepository = userRepository;
        this.grantRepository = grantRepository;
        this.executionRepository = executionRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public AgentGrantEntity createGrant(
            String ownerId,
            CreateAgentGrantRequest request
    ) {
        AgentGrantEntity grant = grantRepository.save(new AgentGrantEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.scopes(),
                request.expiresAt(),
                Instant.now()
        ));
        audit(ownerId, "GRANT_CREATED", "AGENT_GRANT", grant.getId(), request.scopes().toString());
        return grant;
    }

    @Transactional(readOnly = true)
    public List<AgentGrantEntity> listGrants(String ownerId) {
        return grantRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional
    public AgentExecutionEntity createExecution(CreateAgentExecutionRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return executionRepository
                .findByOwnerIdAndIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> createNewExecution(request));
    }

    @Transactional
    public AgentExecutionEntity confirm(String ownerId, String executionId) {
        AgentExecutionEntity execution = requireOwnedExecution(ownerId, executionId);
        execution.confirm(Instant.now());
        audit(ownerId, "EXECUTION_CONFIRMED", "AGENT_EXECUTION", executionId, "用户已确认");
        return execution;
    }

    @Transactional
    public AgentExecutionEntity update(
            String executionId,
            UpdateAgentExecutionRequest request
    ) {
        AgentExecutionEntity execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 执行不存在"));
        execution.update(
                request.status(),
                request.resultSummary(),
                request.errorMessage(),
                request.modelName(),
                request.promptTokens(),
                request.completionTokens(),
                request.latencyMs(),
                request.estimatedCost(),
                Instant.now()
        );
        audit(
                execution.getOwnerId(),
                "EXECUTION_STATUS_CHANGED",
                "AGENT_EXECUTION",
                executionId,
                "状态变更为 " + request.status()
        );
        return execution;
    }

    @Transactional(readOnly = true)
    public List<AgentExecutionEntity> listExecutions(String ownerId) {
        return executionRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public List<AuditLogEntity> listAuditLogs(String ownerId) {
        return auditRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    private AgentExecutionEntity createNewExecution(CreateAgentExecutionRequest request) {
        Instant now = Instant.now();
        boolean granted = grantRepository.findAllByOwnerIdOrderByCreatedAtDesc(request.ownerId())
                .stream()
                .anyMatch(grant -> grant.includes(request.requiredScope(), now));
        ExecutionStatus initialStatus;
        if (request.riskLevel() == RiskLevel.HIGH) {
            initialStatus = ExecutionStatus.WAITING_CONFIRMATION;
        } else if (!granted) {
            initialStatus = ExecutionStatus.WAITING_AUTHORIZATION;
        } else {
            initialStatus = ExecutionStatus.PENDING;
        }
        AgentExecutionEntity execution = executionRepository.save(new AgentExecutionEntity(
                UUID.randomUUID().toString(),
                request.ownerId(),
                request.idempotencyKey(),
                request.executionType(),
                request.triggerType(),
                request.riskLevel(),
                request.requiredScope(),
                initialStatus,
                request.summary(),
                now
        ));
        audit(
                request.ownerId(),
                "EXECUTION_CREATED",
                "AGENT_EXECUTION",
                execution.getId(),
                "初始状态 " + initialStatus
        );
        return execution;
    }

    private AgentExecutionEntity requireOwnedExecution(String ownerId, String executionId) {
        return executionRepository.findByIdAndOwnerId(executionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 执行不存在"));
    }

    private void audit(
            String ownerId,
            String action,
            String targetType,
            String targetId,
            String details
    ) {
        auditRepository.save(new AuditLogEntity(
                ownerId,
                action,
                targetType,
                targetId,
                details,
                Instant.now()
        ));
    }
}
