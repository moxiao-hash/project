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

    /** 日费用上限无法保守执行时的机器可读原因。 */
    public static final String REASON_PRICE_UNKNOWN = "BUDGET_PRICE_UNKNOWN";
    public static final String REASON_OUTPUT_CAP_MISSING = "BUDGET_OUTPUT_CAP_MISSING";
    public static final String REASON_INPUT_BOUND_MISSING = "BUDGET_INPUT_BOUND_MISSING";
    /** 幂等键已被其他 owner 占用：绝不跨 owner 复用许可或自愈对方的预占。 */
    public static final String REASON_IDEMPOTENCY_OWNER_MISMATCH = "IDEMPOTENCY_KEY_OWNER_MISMATCH";

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
     * 幂等落库一次模型调用，并终结同 id 的预占许可。
     *
     * <p>{@code usageId} 由 AI 侧在每次逻辑调用开始时生成，跨上报重试保持不变。</p>
     *
     * <p><b>用量行与预占终结在同一个事务里提交</b>：只要用量成功落库，对应的预占就
     * 一定已经离开 RESERVED；终结失败会让用量行一起回滚，重试可以完整重放，绝不会留下
     * "已计费用量 + 仍在预占"的双计状态。</p>
     *
     * <p>方法本身刻意不加事务注解：插入撞主键时事务已经回滚，随后必须能在新事务里回读
     * 冲突行。重复上报（先查到既有行，或并发冲突回读）会顺带自愈可能残留的 RESERVED
     * 预占，且不会重复计费。</p>
     */
    public AssistantUsageRecord record(RecordUsageCommand command) {
        var existing = usageRepository.findById(command.usageId());
        if (existing.isPresent()) {
            return duplicate(existing.get(), command);
        }
        try {
            return transactionTemplate.execute(status -> persistNewUsage(command));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            return usageRepository.findById(command.usageId())
                    .map(stored -> duplicate(stored, command))
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    private AssistantUsageRecord persistNewUsage(RecordUsageCommand command) {
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
        usageRepository.saveAndFlush(entity);
        // 同一事务内终结；抛错则整条用量回滚，不会出现"已落库但预占仍 RESERVED"。
        reservationRepository.finalizeReservation(
                command.usageId(), entity.getEstimatedCost(), command.occurredAt());
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
     * <p>同一 owner 的并发预占先锁定预算行，再统计"已终结用量 + 未过期预占"，锁定范围是
     * 整个预占事务，因此每日调用上限不会被突破。</p>
     *
     * <p>日费用上限按"已终结费用 + 在途保守预占 + 本次保守预占"判定：预占成本包含当前
     * 请求的输入上界与配置的单轮输出上限（见
     * {@link ModelPricingCatalog#holdCost(String, Integer, Integer, Instant)}）。缺少输入
     * 上界、缺少输出上限或价格未知/未生效时一律失败关闭并给出机器可读原因，
     * 绝不用 0 或 NULL 预占冒充已计量。只有调用次数预算、以及没有预算配置的 owner
     * 不受这些新增限制影响。</p>
     */
    public BudgetPermit reserve(ReserveUsageCommand command, Instant at) {
        var existing = reservationRepository.findById(command.usageId());
        if (existing.isPresent()) {
            return existingPermit(existing.get(), command);
        }
        try {
            return transactionTemplate.execute(status -> reserveInTransaction(command, at));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // 同一 usageId 的并发重试：一个事务插入成功，另一个回读既有预占。
            return reservationRepository.findById(command.usageId())
                    .map(found -> existingPermit(found, command))
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
        AssistantUsageBudgetEntity budget = configured.orElse(null);
        Integer maxOutputTokens = null;
        BigDecimal holdCost = null;
        boolean hasBudget = false;
        if (budget != null) {
            maxOutputTokens = budget.getMaxOutputTokensPerTurn();
            boolean costCeiling = budget.getDailyEstimatedCost() != null;
            hasBudget = costCeiling || budget.getDailyModelCalls() != null;
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
            if (budget.getDailyModelCalls() != null && calls >= budget.getDailyModelCalls()) {
                return denied("DAILY_MODEL_CALLS_EXHAUSTED", maxOutputTokens, zone);
            }
            if (costCeiling) {
                // 费用上限必须能保守执行，否则宁可拒绝这次模型调用。
                if (command.inputTokensUpperBound() == null) {
                    return denied(REASON_INPUT_BOUND_MISSING, maxOutputTokens, zone);
                }
                if (maxOutputTokens == null || maxOutputTokens <= 0) {
                    return denied(REASON_OUTPUT_CAP_MISSING, maxOutputTokens, zone);
                }
                holdCost = pricingCatalog.holdCost(
                                command.modelName(), command.inputTokensUpperBound(),
                                maxOutputTokens, at)
                        .orElse(null);
                if (holdCost == null) {
                    return denied(REASON_PRICE_UNKNOWN, maxOutputTokens, zone);
                }
                BigDecimal cost = sumCost(today).add(sumReservedCost(holds));
                if (cost.add(holdCost).compareTo(budget.getDailyEstimatedCost()) > 0) {
                    return denied("DAILY_ESTIMATED_COST_EXHAUSTED", maxOutputTokens, zone);
                }
            } else if (maxOutputTokens != null) {
                // 只有调用次数预算：输出上界仅作诊断，未知价格不阻塞。
                holdCost = pricingCatalog
                        .outputHoldCost(command.modelName(), maxOutputTokens, at)
                        .orElse(null);
            }
        }
        Instant expiresAt = at.plusSeconds(budgetProperties.getReservationTtlSeconds());
        reservationRepository.saveAndFlush(new AssistantUsageReservationEntity(
                command.usageId(),
                command.ownerId(),
                command.conversationId(),
                command.turnId(),
                command.purpose(),
                command.provider(),
                command.modelName(),
                command.inputTokensUpperBound(),
                maxOutputTokens,
                holdCost,
                at,
                expiresAt));
        return new BudgetPermit(
                command.usageId(),
                true,
                hasBudget ? "WITHIN_BUDGET" : "NO_BUDGET_CONFIGURED",
                maxOutputTokens,
                zone.getId(),
                expiresAt);
    }

    /**
     * 幂等重试命中既有预占时的回执。
     *
     * <p>{@code usageId} 是全局幂等键，只按 id 查表还不够：若该 id 已属于另一个 owner，
     * 把它当成本次预占会直接跨 owner 复用许可。这里失败关闭，并保持对方预占原样。</p>
     */
    private BudgetPermit existingPermit(
            AssistantUsageReservationEntity reservation,
            ReserveUsageCommand command
    ) {
        if (!reservation.getOwnerId().equals(command.ownerId())) {
            return denied(REASON_IDEMPOTENCY_OWNER_MISMATCH,
                    reservation.getMaxOutputTokens(), budgetProperties.zoneId());
        }
        return settlePermit(reservation);
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

    /**
     * 重复上报的自愈路径。
     *
     * <p>用例量行里已落库的真实金额补终结可能残留的 RESERVED 预占：同一次调用绝不能
     * 既算已计费用量、又继续占着在途名额直到 TTL。终结是幂等的，已终结/已释放的行不受影响。</p>
     */
    private AssistantUsageRecord duplicate(
            AssistantModelUsageEntity stored,
            RecordUsageCommand command
    ) {
        // usageId 属于另一个 owner 时，既不能把对方的数字当成本次重复上报返回，
        // 更不能替对方终结预占；新的自愈路径必须先校验归属。
        if (!stored.getOwnerId().equals(command.ownerId())) {
            throw new IllegalStateException("usageId 已归属于其他 owner，拒绝跨 owner 上报");
        }
        reservationRepository.finalizeReservation(
                stored.getId(), stored.getEstimatedCost(), command.occurredAt());
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
