package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantUsageServiceTest {

    private static final String MODEL = "test-model";

    private final AssistantModelUsageJpaRepository usageRepository =
            mock(AssistantModelUsageJpaRepository.class);
    private final AssistantUsageBudgetJpaRepository budgetRepository =
            mock(AssistantUsageBudgetJpaRepository.class);
    private final ModelPricingCatalog catalog = ModelPricingCatalog.of(Map.of(MODEL,
            new ModelPricingCatalog.PriceSpec("test-2026-09-18", LocalDate.of(2026, 9, 18),
                    new BigDecimal("2.00"), new BigDecimal("0.50"), new BigDecimal("8.00"), "CNY")));
    private final AssistantUsageService service =
            new AssistantUsageService(usageRepository, budgetRepository, catalog);

    @Test
    void duplicateCallbackForSameTurnIsNotChargedTwice() {
        when(usageRepository.findByExecutionIdAndTurnId("e1", "t1"))
                .thenReturn(Optional.of(existing()));
        var result = service.record(new RecordUsageCommand(
                "owner", "e1", "t1", "deepseek",
                new ModelUsage(MODEL, 100, 0, 10, null, 20L), Instant.now()));
        assertThat(result.duplicate()).isTrue();
        verify(usageRepository, never()).save(any());
    }

    @Test
    void unknownPriceRecordsUsageWithoutCost() {
        when(usageRepository.findByExecutionIdAndTurnId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        var result = service.record(new RecordUsageCommand(
                "owner", "e1", "t2", "deepseek",
                new ModelUsage("unknown-model", 100, 0, 10, null, 20L), Instant.now()));
        assertThat(result.estimatedCost()).isNull();
        assertThat(result.priceVersion()).isNull();
        verify(usageRepository).save(any());
    }

    @Test
    void knownPricePersistsDecimalCostAndPriceVersion() {
        when(usageRepository.findByExecutionIdAndTurnId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        var result = service.record(new RecordUsageCommand(
                "owner", "e1", "t3", "deepseek",
                new ModelUsage(MODEL, 1_000, 0, 1_000, null, 20L), Instant.now()));
        assertThat(result.estimatedCost()).isNotNull();
        assertThat(result.priceVersion()).isEqualTo("test-2026-09-18");
    }

    @Test
    void budgetExhaustedRejectsNewModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(
                new AssistantUsageBudgetEntity("owner", 3, new BigDecimal("1.00"), 4096, "CNY", Instant.now())));
        when(usageRepository.findAllByOwnerIdAndOccurredAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(usage(), usage(), usage()));
        var decision = service.checkBudget("owner", Instant.now());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isNotBlank();
    }

    @Test
    void budgetWithinLimitsAllowsModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(
                new AssistantUsageBudgetEntity("owner", 3, new BigDecimal("1.00"), 4096, "CNY", Instant.now())));
        when(usageRepository.findAllByOwnerIdAndOccurredAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(usage()));
        assertThat(service.checkBudget("owner", Instant.now()).allowed()).isTrue();
    }

    @Test
    void missingBudgetConfigurationDoesNotBlockModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.empty());
        assertThat(service.checkBudget("owner", Instant.now()).allowed()).isTrue();
    }

    @Test
    void budgetIsScopedToTheOwningUser() {
        when(budgetRepository.findById("other")).thenReturn(Optional.empty());
        assertThat(service.checkBudget("other", Instant.now()).allowed()).isTrue();
        verify(usageRepository, never()).findAllByOwnerIdAndOccurredAtBetween(anyString(), any(), any());
    }

    private AssistantModelUsageEntity existing() {
        return new AssistantModelUsageEntity("u1", "owner", "e1", "t1", "deepseek", MODEL,
                100, 0, 10, null, 20L, new BigDecimal("0.001"), "CNY", "test-2026-09-18", Instant.now());
    }

    private AssistantModelUsageEntity usage() {
        return new AssistantModelUsageEntity("u2", "owner", "e2", "t2", "deepseek", MODEL,
                100, 0, 10, null, 20L, new BigDecimal("0.5"), "CNY", "test-2026-09-18", Instant.now());
    }
}
