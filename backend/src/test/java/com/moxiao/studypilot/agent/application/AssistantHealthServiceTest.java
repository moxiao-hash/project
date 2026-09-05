package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.domain.*;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AssistantHealthServiceTest {
    @Test
    void aggregatesOnlyOwnedRecordsAndExcludesPendingFromSuccessDenominator() {
        var repository = mock(AgentExecutionJpaRepository.class);
        var success = execution("a", ExecutionStatus.SUCCEEDED);
        success.update(ExecutionStatus.SUCCEEDED, null, null, "test-model",
                100, 50, 120L, new BigDecimal("0.002"), Instant.now());
        when(repository.findAllByOwnerIdOrderByCreatedAtDesc("owner"))
                .thenReturn(List.of(success, execution("b", ExecutionStatus.FAILED),
                        execution("c", ExecutionStatus.WAITING_CONFIRMATION)));
        var result = new AssistantHealthService(repository).summarize("owner");
        assertThat(result.totalExecutions()).isEqualTo(3);
        assertThat(result.successRate()).isEqualTo(0.5);
        assertThat(result.promptTokens()).isEqualTo(100);
        assertThat(result.completionTokens()).isEqualTo(50);
        assertThat(result.estimatedCost()).isEqualByComparingTo("0.002");
        assertThat(result.averageLatencyMs()).isEqualTo(120);
        assertThat(result.costSamples()).isEqualTo(1);
        assertThat(result.tokenSamples()).isEqualTo(1);
        assertThat(result.latencySamples()).isEqualTo(1);
        assertThat(result.pendingConfirmations()).isEqualTo(1);
        verify(repository).findAllByOwnerIdOrderByCreatedAtDesc("owner");
        verifyNoMoreInteractions(repository);
    }

    private AgentExecutionEntity execution(String id, ExecutionStatus status) {
        return new AgentExecutionEntity(id, "owner", id, ExecutionType.PLAN_GENERATION,
                TriggerType.USER_REQUEST, RiskLevel.LOW, AgentScope.PLAN_GENERATION,
                status, "test", Instant.now());
    }
}
