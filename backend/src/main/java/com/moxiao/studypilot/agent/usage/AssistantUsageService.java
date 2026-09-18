package com.moxiao.studypilot.agent.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** 模型用量的幂等落库、计价与用户预算判定。 */
@Service
public class AssistantUsageService {

    private final AssistantModelUsageJpaRepository usageRepository;
    private final AssistantUsageBudgetJpaRepository budgetRepository;
    private final ModelPricingCatalog pricingCatalog;

    public AssistantUsageService(
            AssistantModelUsageJpaRepository usageRepository,
            AssistantUsageBudgetJpaRepository budgetRepository,
            ModelPricingCatalog pricingCatalog
    ) {
        this.usageRepository = usageRepository;
        this.budgetRepository = budgetRepository;
        this.pricingCatalog = pricingCatalog;
    }

    @Transactional
    public AssistantUsageRecord record(RecordUsageCommand command) {
        var existing = usageRepository.findById(command.usageId());
        if (existing.isPresent()) {
            var stored = existing.get();
            return new AssistantUsageRecord(stored.getId(), stored.getEstimatedCost(),
                    stored.getCurrency(), stored.getPriceVersion(), true);
        }
        ModelUsage usage = command.usage();
        var estimate = pricingCatalog.estimate(usage.modelName(), usage);
        var entity = new AssistantModelUsageEntity(
                command.usageId(),
                command.ownerId(),
                command.conversationId(),
                command.turnId(),
                command.executionId(),
                command.provider(),
                usage.modelName(),
                (int) usage.promptTokens(),
                (int) usage.cachedPromptTokens(),
                (int) usage.completionTokens(),
                usage.reasoningTokens(),
                usage.latencyMs(),
                estimate.map(ModelPricingCatalog.EstimatedCost::amount).orElse(null),
                estimate.map(ModelPricingCatalog.EstimatedCost::currency).orElse(null),
                estimate.map(ModelPricingCatalog.EstimatedCost::priceVersion).orElse(null),
                command.occurredAt());
        usageRepository.save(entity);
        return new AssistantUsageRecord(entity.getId(), entity.getEstimatedCost(),
                entity.getCurrency(), entity.getPriceVersion(), false);
    }

    @Transactional(readOnly = true)
    public BudgetDecision checkBudget(String ownerId, Instant at) {
        var configured = budgetRepository.findById(ownerId);
        if (configured.isEmpty()) {
            return new BudgetDecision(true, "NO_BUDGET_CONFIGURED", 0L, BigDecimal.ZERO);
        }
        var budget = configured.get();
        ZoneId zone = ZoneId.systemDefault();
        Instant dayStart = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));
        List<AssistantModelUsageEntity> today =
                usageRepository.findAllByOwnerIdAndOccurredAtBetween(ownerId, dayStart, dayEnd);
        long calls = today.size();
        BigDecimal cost = today.stream()
                .map(AssistantModelUsageEntity::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (budget.getDailyModelCalls() != null && calls >= budget.getDailyModelCalls()) {
            return new BudgetDecision(false, "DAILY_MODEL_CALLS_EXHAUSTED", calls, cost);
        }
        if (budget.getDailyEstimatedCost() != null && cost.compareTo(budget.getDailyEstimatedCost()) >= 0) {
            return new BudgetDecision(false, "DAILY_ESTIMATED_COST_EXHAUSTED", calls, cost);
        }
        return new BudgetDecision(true, "WITHIN_BUDGET", calls, cost);
    }
}
