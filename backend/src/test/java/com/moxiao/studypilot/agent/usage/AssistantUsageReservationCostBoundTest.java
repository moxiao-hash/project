package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发日费用上限必须真实可信。
 *
 * <p>旧实现的 `reserved_cost` 只覆盖输出上限，且在未配置 `maxOutputTokensPerTurn` 时为
 * NULL；输入成本完全不计入，于是并发调用可以一起跨过日费用上限，或者在上限存在时给出
 * 一个"零成本"预占。这里固定官方 Flash 单价（高峰 cache-miss 0.30/M、输出 1.20/M），
 * 断言保守预占 = 输入上界 + 输出上限，且不可保守执行时失败关闭。</p>
 */
@SpringBootTest
class AssistantUsageReservationCostBoundTest {

    /** 2026-09-21 是周一，02:00 UTC 属高峰时段。 */
    private static final Instant PEAK = Instant.parse("2026-09-21T02:00:00Z");
    private static final int INPUT_BOUND = 2_000;
    private static final int OUTPUT_CAP = 512;
    /** (2000 × 0.30 + 512 × 1.20) / 1e6 = 0.00121440 USD。 */
    private static final BigDecimal EXPECTED_HOLD = new BigDecimal("0.00121440");

    @Autowired
    private AssistantUsageService service;

    @Autowired
    private AssistantUsageBudgetJpaRepository budgetRepository;

    @Autowired
    private AssistantUsageReservationJpaRepository reservationRepository;

    @Autowired
    private AssistantModelUsageJpaRepository usageRepository;

    @Test
    void conservativeHoldCoversPromptAndOutputBounds() {
        String owner = owner();
        budget(owner, null, "1.00", OUTPUT_CAP);

        BudgetPermit permit = service.reserve(command(owner, "bound-1", INPUT_BOUND), PEAK);
        assertThat(permit.allowed()).isTrue();

        var stored = reservationRepository.findById(scoped(owner, "bound-1")).orElseThrow();
        assertThat(stored.getReservedCost()).isEqualByComparingTo(EXPECTED_HOLD);
        assertThat(stored.getInputTokensUpperBound()).isEqualTo(INPUT_BOUND);
        assertThat(stored.getMaxOutputTokens()).isEqualTo(OUTPUT_CAP);
    }

    @Test
    void promptHeavyRequestIsDeniedBeforeTheProviderCall() {
        String owner = owner();
        // 输出上限 8 的输出成本只有 0.0000096，旧实现会放行；输入上界 10000 的成本是 0.003。
        budget(owner, null, "0.001", 8);

        BudgetPermit permit = service.reserve(command(owner, "prompt-heavy-1", 10_000), PEAK);

        assertThat(permit.allowed()).isFalse();
        assertThat(permit.reason()).isEqualTo("DAILY_ESTIMATED_COST_EXHAUSTED");
        assertThat(reservationRepository.findById(scoped(owner, "prompt-heavy-1"))).isEmpty();
    }

    @Test
    void costCeilingWithoutOutputCapFailsClosed() {
        String owner = owner();
        budget(owner, null, "1.00", null);

        BudgetPermit permit = service.reserve(command(owner, "no-cap-1", INPUT_BOUND), PEAK);

        assertThat(permit.allowed()).isFalse();
        assertThat(permit.reason()).isEqualTo("BUDGET_OUTPUT_CAP_MISSING");
        assertThat(reservationRepository.findById(scoped(owner, "no-cap-1"))).isEmpty();
    }

    @Test
    void costCeilingWithUnknownPriceFailsClosed() {
        String owner = owner();
        budget(owner, null, "1.00", OUTPUT_CAP);

        BudgetPermit permit = service.reserve(
                new ReserveUsageCommand(scoped(owner, "unknown-1"), owner, "conversation-1", "turn-1",
                        "KNOWLEDGE_QA", "deepseek", "unknown-model", INPUT_BOUND),
                PEAK);

        assertThat(permit.allowed()).isFalse();
        assertThat(permit.reason()).isEqualTo("BUDGET_PRICE_UNKNOWN");
        assertThat(reservationRepository.findById(scoped(owner, "unknown-1"))).isEmpty();
    }

    @Test
    void costCeilingBeforePriceEffectiveAtFailsClosed() {
        String owner = owner();
        budget(owner, null, "1.00", OUTPUT_CAP);
        // 2026-08-16T16:00:00Z 之前的价格尚未生效，不能按零成本预占。
        Instant before = Instant.parse("2026-08-16T15:59:59Z");

        BudgetPermit permit = service.reserve(command(owner, "not-effective-1", INPUT_BOUND), before);

        assertThat(permit.allowed()).isFalse();
        assertThat(permit.reason()).isEqualTo("BUDGET_PRICE_UNKNOWN");
    }

    @Test
    void costCeilingWithoutInputBoundFailsClosed() {
        String owner = owner();
        budget(owner, null, "1.00", OUTPUT_CAP);

        BudgetPermit permit = service.reserve(
                new ReserveUsageCommand(scoped(owner, "no-bound-1"), owner, "conversation-1", "turn-1",
                        "KNOWLEDGE_QA", "deepseek", "deepseek-flash", null),
                PEAK);

        assertThat(permit.allowed()).isFalse();
        assertThat(permit.reason()).isEqualTo("BUDGET_INPUT_BOUND_MISSING");
    }

    @Test
    void concurrentReservationsCannotCrossTheCostCeiling() throws Exception {
        String owner = owner();
        // 上限 0.0015：只够一次在途调用的保守预占 0.0012144。
        budget(owner, null, "0.0015", OUTPUT_CAP);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<BudgetPermit> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.reserve(
                        command(owner, "race-" + Thread.currentThread().getName(), INPUT_BOUND), PEAK);
            };
            Future<BudgetPermit> a = pool.submit(named("a", attempt));
            Future<BudgetPermit> b = pool.submit(named("b", attempt));
            List<BudgetPermit> permits =
                    List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

            assertThat(permits.stream().filter(BudgetPermit::allowed).count()).isEqualTo(1);
            assertThat(permits.stream().filter(permit -> !permit.allowed())
                    .map(BudgetPermit::reason))
                    .containsExactly("DAILY_ESTIMATED_COST_EXHAUSTED");
            assertThat(reservationRepository.findAllByOwnerIdAndStateAndExpiresAtAfter(
                    owner, AssistantUsageReservationEntity.STATE_RESERVED, PEAK))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releasedCostHoldFreesTheCeilingForTheNextCall() {
        String owner = owner();
        budget(owner, null, "0.0015", OUTPUT_CAP);
        BudgetPermit permit = service.reserve(command(owner, "hold-1", INPUT_BOUND), PEAK);
        assertThat(permit.allowed()).isTrue();
        assertThat(service.reserve(command(owner, "hold-2", INPUT_BOUND), PEAK).allowed()).isFalse();

        assertThat(service.release(permit.reservationId(), PEAK)).isTrue();
        assertThat(service.reserve(command(owner, "hold-3", INPUT_BOUND), PEAK).allowed()).isTrue();
    }

    @Test
    void finalizedActualCostReplacesTheHoldSoTheNextCallFits() {
        String owner = owner();
        budget(owner, null, "0.0015", OUTPUT_CAP);
        // command() 内部已按 owner 加后缀；record 必须用同一个最终 id 才能终结该预占。
        String usageId = scoped(owner, "finalize-cost-1");
        assertThat(service.reserve(command(owner, "finalize-cost-1", INPUT_BOUND), PEAK).allowed())
                .isTrue();

        // 实际用量远小于保守上界：终结后占用的是真实金额，而不是继续占着上界。
        service.record(new RecordUsageCommand(
                usageId, owner, "conversation-1", "turn-1", null, "deepseek",
                "KNOWLEDGE_QA", "SUCCEEDED",
                new ModelUsage("deepseek-flash", 100, 0, 10, null, 20L), PEAK));

        assertThat(service.reserve(command(owner, "finalize-cost-2", INPUT_BOUND), PEAK).allowed())
                .isTrue();
    }

    @Test
    void callsOnlyBudgetIgnoresUnknownPriceAndMissingCap() {
        String owner = owner();
        budget(owner, 2, null, null);

        BudgetPermit permit = service.reserve(
                new ReserveUsageCommand(scoped(owner, "calls-only-1"), owner, "conversation-1", "turn-1",
                        "KNOWLEDGE_QA", "deepseek", "unknown-model", INPUT_BOUND),
                PEAK);

        assertThat(permit.allowed()).isTrue();
        assertThat(reservationRepository.findById(scoped(owner, "calls-only-1")).orElseThrow().getReservedCost())
                .isNull();
    }

    @Test
    void ownerWithoutBudgetConfigurationIsStillAllowed() {
        String owner = owner();

        BudgetPermit permit = service.reserve(command(owner, "no-budget-1", INPUT_BOUND), PEAK);

        assertThat(permit.allowed()).isTrue();
        assertThat(permit.reason()).isEqualTo("NO_BUDGET_CONFIGURED");
    }

    @Test
    void retriedReservationKeepsTheFirstBoundAndASingleRow() {
        String owner = owner();
        budget(owner, null, "1.00", OUTPUT_CAP);

        BudgetPermit first = service.reserve(command(owner, "retry-bound-1", INPUT_BOUND), PEAK);
        BudgetPermit retry = service.reserve(command(owner, "retry-bound-1", 99_999), PEAK);

        assertThat(first.allowed()).isTrue();
        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(reservationRepository.findAllByOwnerIdAndStateAndExpiresAtAfter(
                owner, AssistantUsageReservationEntity.STATE_RESERVED, PEAK)).hasSize(1);
        assertThat(reservationRepository.findById(scoped(owner, "retry-bound-1")).orElseThrow()
                .getInputTokensUpperBound()).isEqualTo(INPUT_BOUND);
    }

    private static String owner() {
        return "owner-cost-bound-" + System.nanoTime();
    }

    private void budget(String owner, Integer dailyCalls, String dailyCost, Integer outputCap) {
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, dailyCalls,
                dailyCost == null ? null : new BigDecimal(dailyCost),
                outputCap, "USD", Instant.now()));
    }

    /** 幂等键按 owner 加后缀，避免共享测试库里的跨类撞键。 */
    private static String scoped(String owner, String usageId) {
        return usageId + "-" + owner.substring(owner.length() - 12);
    }

    private static ReserveUsageCommand command(String owner, String usageId, Integer inputBound) {
        return new ReserveUsageCommand(scoped(owner, usageId), owner, "conversation-1", "turn-1",
                "KNOWLEDGE_QA", "deepseek", "deepseek-flash", inputBound);
    }

    private static <T> Callable<T> named(String name, Callable<T> delegate) {
        return () -> {
            Thread.currentThread().setName(name);
            return delegate.call();
        };
    }
}
