package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.api.AssistantHealthResponse;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AssistantHealthService {

    private final AgentExecutionJpaRepository repository;

    public AssistantHealthService(AgentExecutionJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AssistantHealthResponse summarize(String ownerId) {
        List<AgentExecutionEntity> executions =
                repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        int succeeded = count(executions, ExecutionStatus.SUCCEEDED);
        int failed = count(executions, ExecutionStatus.FAILED);
        int decided = succeeded + failed;
        long latencySamples = executions.stream()
                .filter(item -> item.getLatencyMs() != null).count();
        long totalLatency = executions.stream()
                .map(AgentExecutionEntity::getLatencyMs)
                .filter(value -> value != null).mapToLong(Long::longValue).sum();
        BigDecimal cost = executions.stream()
                .map(AgentExecutionEntity::getEstimatedCost)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AssistantHealthResponse(
                executions.size(), succeeded, failed,
                decided == 0 ? 0.0 : (double) succeeded / decided,
                sumTokens(executions, true), sumTokens(executions, false), cost,
                latencySamples == 0 ? 0 : totalLatency / latencySamples,
                count(executions, ExecutionStatus.WAITING_CONFIRMATION),
                executions.stream().filter(item -> item.getEstimatedCost() != null).count(),
                executions.stream().filter(item -> item.getPromptTokens() != null
                        && item.getCompletionTokens() != null).count(),
                latencySamples);
    }

    private static int count(List<AgentExecutionEntity> values, ExecutionStatus status) {
        return (int) values.stream().filter(value -> value.getStatus() == status).count();
    }

    private static long sumTokens(List<AgentExecutionEntity> values, boolean prompt) {
        return values.stream().mapToLong(value -> {
            Integer tokens = prompt ? value.getPromptTokens() : value.getCompletionTokens();
            return tokens == null ? 0 : tokens;
        }).sum();
    }
}
