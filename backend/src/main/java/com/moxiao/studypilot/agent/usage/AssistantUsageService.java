package com.moxiao.studypilot.agent.usage;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AssistantUsageReservationJpaRepository reservationRepository;
    private final ModelPricingCatalog pricingCatalog;
    private final AssistantBudgetProperties budgetProperties;
    private final TransactionTemplate transactionTemplate;

    public AssistantUsageService(
            AssistantModelUsageJpaRepository usageRepository,
            AssistantUsageBudgetJpaRepository budgetRepository,
            AssistantUsageReservationJpaRepository reservationRepository,
            ModelPricingCatalog pricingCatalog,
            AssistantBudgetProperties budgetProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.usageRepository = usageRepository;
        this.budgetRepository = budgetRepository;
        this.reservationRepository = reservationRepository;
        this.pricingCatalog = pricingCatalog;
        this.budgetProperties = budgetProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等落库一次模型调用。
     *
     * <p>{@code usageId} 由 AI 侧在每次逻辑调用开始时生成，跨上报重试保持不变；
     * 并发重复回调由主键约束兜底：先查、插入冲突后回读，都返回 {@code duplicate=true}。
     * 本方法刻意不加外层事务，插入冲突回滚后仍能在新事务里回读已存在的记录。
     * 落库成功后同时按实际金额终结同 id 的预占许可；重复回调是无操作。</p>
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
        reservationRepository.finalizeReservation(
                command.usageId(),
                estimate.map(ModelPricingCatalog.EstimatedCost::amount).orElse(null),
                command.occurredAt());
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
     * 在真实 provider 调用之前原子预占一个模型调用许可。
     *
     * <p>同一 owner 的并发预占必须先锁定预算行再统计"已终结用量 + 未过期预占"，
     * 否则两个并发请求会各自读到还剩一个名额并同时放行。锁定范围是整个
     * 预占事务：第一个事务提交后第二个事务才能继续，因此每日调用/费用上限不会被突破。</p>
     *
     * <p>没有配置预算的 owner 直接放行且不写预占行；此时不存在需要保护的上限。</p>
     */
    public BudgetPermit reserve(ReserveUsageCommand command, Instant at) {
        var existing = reservationRepository.findById(command.usageId());
        if (existing.isPresent()) {
            return settlePermit(existing.get());
        }
        try {
            return transactionTemplate.execute(status -> reserveInTransaction(command, at));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // 同一 usageId 的并发重试：一个事务插入成功，另一个回读既有预占。
            return reservationRepository.findById(command.usageId())
                    .map(this::settlePermit)
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    /** 终结或释放预占；重复调用是无操作，返回是否真的改变了状态。 */
    @Transactional
    public boolean release(String reservationId, Instant at) {
        return reservationRepository.releaseReservation(reservationId, at) > 0;
    }

    private BudgetPermit reserveInTransaction(ReserveUsageCommand command, Instant at) {
        ZoneId zone = budgetProperties.zoneId();
        var configured = budgetRepository.findByOwnerIdForUpdate(command.ownerId());
        Integer maxOutputTokens = null;
        boolean withinBudget = true;
        if (configured.isPresent()) {
            var budget = configured.get();
            maxOutputTokens = budget.getMaxOutputTokensPerTurn();
            Instant[] window = dayWindow(at, zone);
            List<AssistantModelUsageEntity> today = usageRepository
                    .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                            command.ownerId(), window[0], window[1]);
            List<AssistantUsageReservationEntity> holds =
                    reservationRepository.findAllByOwnerIdAndStateAndExpiresAtAfter(
                            command.ownerId(),
                            AssistantUsageReservationEntity.STATE_RESERVED,
                            at);
            long calls = today.size() + holds.size();
            BigDecimal cost = sumCost(today).add(sumReservedCost(holds));
            if (budget.getDailyModelCalls() != null && calls >= budget.getDailyModelCalls()) {
                return denied("DAILY_MODEL_CALLS_EXHAUSTED", maxOutputTokens, zone);
            }
            if (budget.getDailyEstimatedCost() != null
                    && cost.compareTo(budget.getDailyEstimatedCost()) >= 0) {
                return denied("DAILY_ESTIMATED_COST_EXHAUSTED", maxOutputTokens, zone);
            }
            withinBudget = budget.getDailyModelCalls() != null
                    || budget.getDailyEstimatedCost() != null;
        }
        BigDecimal holdCost = maxOutputTokens == null
                ? null
                : pricingCatalog.outputHoldCost(command.modelName(), maxOutputTokens, at)
                        .orElse(null);
        Instant expiresAt = at.plusSeconds(budgetProperties.getReservationTtlSeconds());
        reservationRepository.saveAndFlush(new AssistantUsageReservationEntity(
                command.usageId(),
                command.ownerId(),
                command.conversationId(),
                command.turnId(),
                command.purpose(),
                command.provider(),
                command.modelName(),
                maxOutputTokens,
                holdCost,
                at,
                expiresAt));
        return new BudgetPermit(
                command.usageId(),
                true,
                withinBudget ? "WITHIN_BUDGET" : "NO_BUDGET_CONFIGURED",
                maxOutputTokens,
                zone.getId(),
                expiresAt);
    }

    private BudgetPermit settlePermit(AssistantUsageReservationEntity reservation) {
        String state = reservation.getState();
        boolean reserved = AssistantUsageReservationEntity.STATE_RESERVED.equals(state);
        return new BudgetPermit(
                reservation.getId(),
                reserved,
                reserved ? "WITHIN_BUDGET" : "RESERVATION_" + state,
                reservation.getMaxOutputTokens(),
                budgetProperties.zoneId().getId(),
                reservation.getExpiresAt());
    }

    private BudgetPermit denied(String reason, Integer maxOutputTokens, ZoneId zone) {
        return new BudgetPermit(null, false, reason, maxOutputTokens, zone.getId(), null);
    }

    private static Instant[] dayWindow(Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        return new Instant[] {
                local.toLocalDate().atStartOfDay(zone).toInstant(),
                local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        };
    }

    private static BigDecimal sumCost(List<AssistantModelUsageEntity> usages) {
        return usages.stream()
                .map(AssistantModelUsageEntity::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumReservedCost(List<AssistantUsageReservationEntity> holds) {
        return holds.stream()
                .map(AssistantUsageReservationEntity::getReservedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
        Instant[] window = dayWindow(at, zone);
        List<AssistantModelUsageEntity> today =
                usageRepository
                        .findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                                ownerId, window[0], window[1]);
        long calls = today.size();
        BigDecimal cost = sumCost(today);
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
