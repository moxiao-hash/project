package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.domain.*;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.agent.usage.AssistantModelUsageEntity;
import com.moxiao.studypilot.agent.usage.AssistantModelUsageJpaRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class AssistantHealthServiceTest {

    private final AgentExecutionJpaRepository executions = mock(AgentExecutionJpaRepository.class);
    private final AssistantModelUsageJpaRepository usage = mock(AssistantModelUsageJpaRepository.class);
    private final AssistantHealthService service = new AssistantHealthService(executions, usage);

    @Test
    void aggregatesOnlyOwnedRecordsAndExcludesPendingFromSuccessDenominator() {
        var success = execution("a", ExecutionStatus.SUCCEEDED);
        success.update(ExecutionStatus.SUCCEEDED, null, null, "test-model",
                100, 50, 120L, new BigDecimal("0.002"), Instant.now());
        when(executions.findAllByOwnerIdOrderByCreatedAtDesc("owner"))
                .thenReturn(List.of(success, execution("b", ExecutionStatus.FAILED),
                        execution("c", ExecutionStatus.WAITING_CONFIRMATION)));
        when(usage.findAllByOwnerIdOrderByOccurredAtDesc("owner")).thenReturn(List.of());
        var result = service.summarize("owner");
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
        assertThat(result.modelCalls()).isZero();
        assertThat(result.priceStatus()).isEqualTo("NONE");
        assertThat(result.models()).isEmpty();
        verify(executions).findAllByOwnerIdOrderByCreatedAtDesc("owner");
        verify(usage).findAllByOwnerIdOrderByOccurredAtDesc("owner");
        verifyNoMoreInteractions(executions);
    }

    @Test
    void reportsModelLevelTokenCategoriesCostPercentilesAndFailures() {
        when(executions.findAllByOwnerIdOrderByCreatedAtDesc("owner")).thenReturn(List.of());
        when(usage.findAllByOwnerIdOrderByOccurredAtDesc("owner")).thenReturn(List.of(
                usage("u1", "deepseek-flash", "SUCCEEDED", 1_000, 400, 200, 50, 100L,
                        new BigDecimal("0.0011")),
                usage("u2", "deepseek-flash", "FAILED", 0, 0, 0, null, 300L, BigDecimal.ZERO),
                usage("u3", "deepseek-flash", "SUCCEEDED", 2_000, 0, 500, 100, 200L,
                        new BigDecimal("0.0033"))));
        var result = service.summarize("owner");
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.failedModelCalls()).isEqualTo(1);
        assertThat(result.modelFailureRate()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(result.modelPromptTokens()).isEqualTo(3_000);
        assertThat(result.modelCachedPromptTokens()).isEqualTo(400);
        assertThat(result.modelUncachedPromptTokens()).isEqualTo(2_600);
        assertThat(result.modelCompletionTokens()).isEqualTo(700);
        assertThat(result.modelReasoningTokens()).isEqualTo(150);
        assertThat(result.modelTotalTokens()).isEqualTo(3_700);
        assertThat(result.usageEstimatedCost()).isEqualByComparingTo("0.0044");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.priceStatus()).isEqualTo("KNOWN");
        assertThat(result.priceVersion()).isEqualTo("deepseek-pricing-2026-09-20");
        assertThat(result.p50LatencyMs()).isEqualTo(200);
        assertThat(result.p95LatencyMs()).isEqualTo(300);
        assertThat(result.models()).hasSize(1);
        var model = result.models().get(0);
        assertThat(model.modelName()).isEqualTo("deepseek-flash");
        assertThat(model.calls()).isEqualTo(3);
        assertThat(model.failedCalls()).isEqualTo(1);
        assertThat(model.cachedPromptTokens()).isEqualTo(400);
        assertThat(model.p50LatencyMs()).isEqualTo(200);
        assertThat(model.p95LatencyMs()).isEqualTo(300);
        assertThat(model.priceStatus()).isEqualTo("KNOWN");
    }

    @Test
    void unknownPriceIsReportedAsUnknownAndNeverAsZero() {
        when(executions.findAllByOwnerIdOrderByCreatedAtDesc("owner")).thenReturn(List.of());
        when(usage.findAllByOwnerIdOrderByOccurredAtDesc("owner")).thenReturn(List.of(
                usage("u1", "unlisted-model", "SUCCEEDED", 1_000, 0, 100, null, 50L, null)));
        var result = service.summarize("owner");
        assertThat(result.unknownPriceCalls()).isEqualTo(1);
        assertThat(result.priceStatus()).isEqualTo("UNKNOWN");
        assertThat(result.usageEstimatedCost()).isNull();
        assertThat(result.models().get(0).priceStatus()).isEqualTo("UNKNOWN");
        assertThat(result.models().get(0).estimatedCost()).isNull();
    }

    @Test
    void queriesAreScopedToTheRequestedOwnerOnly() {
        when(executions.findAllByOwnerIdOrderByCreatedAtDesc("owner")).thenReturn(List.of());
        when(usage.findAllByOwnerIdOrderByOccurredAtDesc("owner")).thenReturn(List.of());
        service.summarize("owner");
        verify(executions).findAllByOwnerIdOrderByCreatedAtDesc("owner");
        verify(usage).findAllByOwnerIdOrderByOccurredAtDesc("owner");
        verifyNoMoreInteractions(usage);
    }

    private AgentExecutionEntity execution(String id, ExecutionStatus status) {
        return new AgentExecutionEntity(id, "owner", id, ExecutionType.PLAN_GENERATION,
                TriggerType.USER_REQUEST, RiskLevel.LOW, AgentScope.PLAN_GENERATION,
                status, "test", Instant.now());
    }

    private AssistantModelUsageEntity usage(
            String id, String model, String status, int prompt, int cached, int completion,
            Integer reasoning, long latencyMs, BigDecimal cost) {
        return new AssistantModelUsageEntity(id, "owner", "conversation-1", "turn-1", null,
                "deepseek", model, "KNOWLEDGE_QA", status, prompt, cached, completion, reasoning,
                prompt + completion, latencyMs, cost, cost == null ? null : "USD",
                cost == null ? null : "deepseek-pricing-2026-09-20",
                cost == null ? null : "OFF_PEAK", Instant.now());
    }
}
