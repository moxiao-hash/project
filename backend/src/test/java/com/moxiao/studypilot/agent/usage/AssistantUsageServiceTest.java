package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantUsageServiceTest {

    private static final String MODEL = "test-model";
    private static final String USAGE_ID = "usage-1";

    private final AssistantModelUsageJpaRepository usageRepository =
            mock(AssistantModelUsageJpaRepository.class);
    private final AssistantUsageBudgetJpaRepository budgetRepository =
            mock(AssistantUsageBudgetJpaRepository.class);
    private final ModelPricingCatalog catalog = ModelPricingCatalog.of(Map.of(MODEL,
            new ModelPricingCatalog.PriceSpec("test-model", "Test-Model-0813",
                    "test-2026-09-18", LocalDate.of(2026, 9, 18), "USD",
                    "https://example.invalid/pricing",
                    new BigDecimal("1.00"), new BigDecimal("0.50"),
                    new BigDecimal("4.00"), new BigDecimal("2.00"),
                    new BigDecimal("16.00"), new BigDecimal("8.00"))));
    private final AssistantBudgetProperties budgetProperties = new AssistantBudgetProperties();
    private final AssistantUsageService service =
            new AssistantUsageService(usageRepository, budgetRepository, catalog, budgetProperties);

    @Test
    void duplicateCallbackForSameUsageIdIsNotChargedTwice() {
        when(usageRepository.findById(USAGE_ID)).thenReturn(Optional.of(existing()));
        var result = service.record(command(USAGE_ID, MODEL));
        assertThat(result.duplicate()).isTrue();
        verify(usageRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateInsertFallsBackToExistingRow() {
        when(usageRepository.findById(USAGE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing()));
        when(usageRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate primary key"));
        var result = service.record(command(USAGE_ID, MODEL));
        assertThat(result.duplicate()).isTrue();
        assertThat(result.estimatedCost()).isEqualByComparingTo("0.001");
    }

    @Test
    void unknownPriceRecordsUsageWithoutCost() {
        when(usageRepository.findById(anyString())).thenReturn(Optional.empty());
        var result = service.record(command(USAGE_ID, "unknown-model"));
        assertThat(result.estimatedCost()).isNull();
        assertThat(result.priceVersion()).isNull();
        assertThat(result.priceStatus()).isEqualTo(AssistantUsageRecord.PRICE_UNKNOWN);
        verify(usageRepository).saveAndFlush(any());
    }

    @Test
    void knownPricePersistsDecimalCostAndPriceVersion() {
        when(usageRepository.findById(anyString())).thenReturn(Optional.empty());
        var result = service.record(command(USAGE_ID, MODEL));
        assertThat(result.estimatedCost()).isNotNull();
        assertThat(result.priceVersion()).isEqualTo("test-2026-09-18");
        assertThat(result.priceStatus()).isEqualTo(AssistantUsageRecord.PRICE_KNOWN);
        assertThat(result.priceWindow()).isIn("PEAK", "OFF_PEAK");
    }

    @Test
    void recordPersistsPurposeStatusAndTotalWithoutChargingReasoningTwice() {
        when(usageRepository.findById(anyString())).thenReturn(Optional.empty());
        service.record(new RecordUsageCommand(USAGE_ID, "owner", "conversation-1", "turn-1", null,
                "deepseek", "QUIZ_GENERATION", "SUCCEEDED",
                new ModelUsage(MODEL, 1_000, 0, 1_000, 800, 20L), Instant.now()));
        ArgumentCaptor<AssistantModelUsageEntity> captor =
                ArgumentCaptor.forClass(AssistantModelUsageEntity.class);
        verify(usageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPurpose()).isEqualTo("QUIZ_GENERATION");
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(captor.getValue().getTotalTokens()).isEqualTo(2_000);
        assertThat(captor.getValue().getReasoningTokens()).isEqualTo(800);
    }

    @Test
    void budgetExhaustedRejectsNewModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(budget(3, "1.00")));
        when(usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(List.of(usage("u1"), usage("u2"), usage("u3")));
        var decision = service.checkBudget("owner", Instant.now());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("DAILY_MODEL_CALLS_EXHAUSTED");
    }

    @Test
    void dailyCostCeilingAlsoBlocksModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(budget(100, "1.00")));
        when(usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(List.of(usage("u1"), usage("u2")));
        var decision = service.checkBudget("owner", Instant.now());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("DAILY_ESTIMATED_COST_EXHAUSTED");
    }

    @Test
    void budgetWithinLimitsAllowsModelCallsAndExposesTurnOutputCap() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(budget(3, "1.00")));
        when(usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(List.of(usage("u1")));
        var decision = service.checkBudget("owner", Instant.now());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.maxOutputTokensPerTurn()).isEqualTo(4096);
        assertThat(decision.timezone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void missingBudgetConfigurationDoesNotBlockModelCalls() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.empty());
        var decision = service.checkBudget("owner", Instant.now());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.timezone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void budgetDayWindowUsesConfiguredTimezoneNotSystemDefault() {
        budgetProperties.setTimezone("America/New_York");
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(budget(10, "10.00")));
        when(usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(List.of());
        // 2026-09-20T04:00:00Z 是纽约当地 2026-09-20 00:00（EDT，UTC-4）。
        service.checkBudget("owner", Instant.parse("2026-09-20T04:00:00Z"));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(usageRepository)
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        eq("owner"), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-20T04:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-09-21T04:00:00Z"));
    }

    @Test
    void budgetDayWindowUsesShanghaiDefaultAcrossUtcMidnight() {
        when(budgetRepository.findById("owner")).thenReturn(Optional.of(budget(10, "10.00")));
        when(usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any()))
                .thenReturn(List.of());
        // 2026-09-20T17:00:00Z 是上海当地 2026-09-21 01:00。
        service.checkBudget("owner", Instant.parse("2026-09-20T17:00:00Z"));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(usageRepository)
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        eq("owner"), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-20T16:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-09-21T16:00:00Z"));
    }

    @Test
    void budgetIsScopedToTheOwningUser() {
        when(budgetRepository.findById("other")).thenReturn(Optional.empty());
        assertThat(service.checkBudget("other", Instant.now()).allowed()).isTrue();
        verify(usageRepository, never())
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        anyString(), any(), any());
    }

    private RecordUsageCommand command(String usageId, String modelName) {
        return new RecordUsageCommand(usageId, "owner", "conversation-1", "turn-1", null, "deepseek",
                "KNOWLEDGE_QA", "SUCCEEDED",
                new ModelUsage(modelName, 1_000, 0, 1_000, null, 20L), Instant.now());
    }

    private AssistantUsageBudgetEntity budget(int dailyCalls, String dailyCost) {
        return new AssistantUsageBudgetEntity("owner", dailyCalls, new BigDecimal(dailyCost),
                4096, "USD", Instant.now());
    }

    private AssistantModelUsageEntity existing() {
        return new AssistantModelUsageEntity(USAGE_ID, "owner", "conversation-1", "turn-1", null,
                "deepseek", MODEL, "KNOWLEDGE_QA", "SUCCEEDED", 100, 0, 10, null, 110, 20L,
                new BigDecimal("0.001"), "USD", "test-2026-09-18", "OFF_PEAK", Instant.now());
    }

    private AssistantModelUsageEntity usage(String id) {
        return new AssistantModelUsageEntity(id, "owner", "conversation-1", "turn-1", null,
                "deepseek", MODEL, "KNOWLEDGE_QA", "SUCCEEDED", 100, 0, 10, null, 110, 20L,
                new BigDecimal("0.5"), "USD", "test-2026-09-18", "OFF_PEAK", Instant.now());
    }
}
