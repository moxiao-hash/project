package com.moxiao.studypilot.agent.usage;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/** 模型用量的幂等落库、计价与用户预算判定。 */
@Service
public class AssistantUsageService {

    private final AssistantModelUsageJpaRepository usageRepository;
    private final AssistantUsageBudgetJpaRepository budgetRepository;
    private final ModelPricingCatalog pricingCatalog;
    private final AssistantBudgetProperties budgetProperties;

    public AssistantUsageService(
            AssistantModelUsageJpaRepository usageRepository,
            AssistantUsageBudgetJpaRepository budgetRepository,
            ModelPricingCatalog pricingCatalog,
            AssistantBudgetProperties budgetProperties
    ) {
        this.usageRepository = usageRepository;
        this.budgetRepository = budgetRepository;
        this.pricingCatalog = pricingCatalog;
        this.budgetProperties = budgetProperties;
    }

    /**
     * 幂等落库一次模型调用。
     *
     * <p>{@code usageId} 由 AI 侧在每次逻辑调用开始时生成，跨上报重试保持不变；
     * 并发重复回调由主键约束兜底：先查、插入冲突后回读，都返回 {@code duplicate=true}。
     * 本方法刻意不加外层事务，插入冲突回滚后仍能在新事务里回读已存在的记录。</p>
     */
    public AssistantUsageRecord record(RecordUsageCommand command) {
        var existing = usageRepository.findById(command.usageId());
        if (existing.isPresent()) {
            return duplicate(existing.get());
        }
        ModelUsage usage = command.usage();
        var estimate = pricingCatalog.estimate(usage.modelName(), usage, command.occurredAt());
        var entity = new AssistantModelUsageEntity(
                command.usageId(),
                command.ownerId(),
                command.conversationId(),
                command.turnId(),
                command.executionId(),
                command.provider(),
                usage.modelName(),
                command.purpose(),
                command.status(),
                (int) usage.promptTokens(),
                (int) usage.cachedPromptTokens(),
                (int) usage.completionTokens(),
                usage.reasoningTokens(),
                (int) usage.totalTokens(),
                usage.latencyMs(),
                estimate.map(ModelPricingCatalog.EstimatedCost::amount).orElse(null),
                estimate.map(ModelPricingCatalog.EstimatedCost::currency).orElse(null),
                estimate.map(ModelPricingCatalog.EstimatedCost::priceVersion).orElse(null),
                estimate.map(ModelPricingCatalog.EstimatedCost::window)
                        .map(Enum::name).orElse(null),
                command.occurredAt());
        try {
            usageRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            return usageRepository.findById(command.usageId())
                    .map(this::duplicate)
                    .orElseThrow(() -> concurrentDuplicate);
        }
        return new AssistantUsageRecord(
                entity.getId(),
                entity.getEstimatedCost(),
                entity.getCurrency(),
                entity.getPriceVersion(),
                entity.getPriceWindow(),
                entity.getEstimatedCost() == null
                        ? AssistantUsageRecord.PRICE_UNKNOWN
                        : AssistantUsageRecord.PRICE_KNOWN,
                false);
    }

    /**
     * 判断某个 owner 当天是否还可以发起新的模型调用。
     *
     * <p>"当天"落在配置的预算时区（默认 Asia/Shanghai），不使用系统默认时区；
     * 区间取半开 {@code [当天 00:00, 次日 00:00)}，次日边界由本地日期加一天再取
     * 当日起点，能正确处理夏令时切换。</p>
     */
    @Transactional(readOnly = true)
    public BudgetDecision checkBudget(String ownerId, Instant at) {
        ZoneId zone = budgetProperties.zoneId();
        var configured = budgetRepository.findById(ownerId);
        if (configured.isEmpty()) {
            return new BudgetDecision(true, "NO_BUDGET_CONFIGURED", 0L, BigDecimal.ZERO,
                    null, zone.getId());
        }
        var budget = configured.get();
        ZonedDateTime local = at.atZone(zone);
        Instant dayStart = local.toLocalDate().atStartOfDay(zone).toInstant();
        Instant nextDayStart = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        List<AssistantModelUsageEntity> today =
                usageRepository
                        .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                                ownerId, dayStart, nextDayStart);
        long calls = today.size();
        BigDecimal cost = today.stream()
                .map(AssistantModelUsageEntity::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (budget.getDailyModelCalls() != null && calls >= budget.getDailyModelCalls()) {
            return new BudgetDecision(false, "DAILY_MODEL_CALLS_EXHAUSTED", calls, cost,
                    budget.getMaxOutputTokensPerTurn(), zone.getId());
        }
        if (budget.getDailyEstimatedCost() != null
                && cost.compareTo(budget.getDailyEstimatedCost()) >= 0) {
            return new BudgetDecision(false, "DAILY_ESTIMATED_COST_EXHAUSTED", calls, cost,
                    budget.getMaxOutputTokensPerTurn(), zone.getId());
        }
        return new BudgetDecision(true, "WITHIN_BUDGET", calls, cost,
                budget.getMaxOutputTokensPerTurn(), zone.getId());
    }

    /** 当天已发生的模型调用数，用于健康与排障；同样按预算时区切日。 */
    @Transactional(readOnly = true)
    public long callsToday(String ownerId, Instant at) {
        ZoneId zone = budgetProperties.zoneId();
        ZonedDateTime local = at.atZone(zone);
        return usageRepository
                .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        ownerId,
                        local.toLocalDate().atStartOfDay(zone).toInstant(),
                        local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())
                .size();
    }

    private AssistantUsageRecord duplicate(AssistantModelUsageEntity stored) {
        return new AssistantUsageRecord(
                stored.getId(),
                stored.getEstimatedCost(),
                stored.getCurrency(),
                stored.getPriceVersion(),
                stored.getPriceWindow(),
                stored.getEstimatedCost() == null
                        ? AssistantUsageRecord.PRICE_UNKNOWN
                        : AssistantUsageRecord.PRICE_KNOWN,
                true);
    }
}
