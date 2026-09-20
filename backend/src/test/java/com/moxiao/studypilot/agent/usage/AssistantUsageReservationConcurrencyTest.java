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
 * 预占许可的数据库级并发语义：一个剩余名额只能被一个并发请求拿到。
 *
 * <p>该用例必须跑真实数据库而不是 Mockito；"先查计数再调用"的竞态只有在真实事务与
 * 行锁下才能被观测和阻止。</p>
 */
@SpringBootTest
class AssistantUsageReservationConcurrencyTest {

    @Autowired
    private AssistantUsageService service;

    @Autowired
    private AssistantUsageBudgetJpaRepository budgetRepository;

    @Autowired
    private AssistantUsageReservationJpaRepository reservationRepository;

    @Test
    void twoConcurrentReservationsAgainstOneRemainingCallYieldExactlyOnePermit() throws Exception {
        String owner = "owner-race-" + System.nanoTime();
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 1, null, 512, "USD", Instant.now()));

        Instant at = Instant.now();
        ReserveUsageCommand first = command(owner, "race-a");
        ReserveUsageCommand second = command(owner, "race-b");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<BudgetPermit> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.reserve(Thread.currentThread().getName().equals("first")
                        ? first : second, at);
            };
            // 固定线程名以便区分两个请求；两个线程同时跨过栅栏再竞争同一预算行。
            Future<BudgetPermit> a = pool.submit(named("first", attempt));
            Future<BudgetPermit> b = pool.submit(named("second", attempt));

            List<BudgetPermit> permits =
                    List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            long allowed = permits.stream().filter(BudgetPermit::allowed).count();

            assertThat(allowed).isEqualTo(1);
            assertThat(permits.stream()
                    .filter(permit -> !permit.allowed())
                    .map(BudgetPermit::reason))
                    .containsExactly("DAILY_MODEL_CALLS_EXHAUSTED");
            assertThat(reservationRepository
                    .findAllByOwnerIdAndStateAndExpiresAtAfter(
                            owner,
                            AssistantUsageReservationEntity.STATE_RESERVED,
                            at))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retriedReservationWithSameUsageIdIsIdempotentAndDoesNotConsumeASecondPermit()
            throws Exception {
        String owner = "owner-idem-" + System.nanoTime();
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 2, null, 512, "USD", Instant.now()));
        Instant at = Instant.now();
        ReserveUsageCommand command = command(owner, "idem-1");

        BudgetPermit first = service.reserve(command, at);
        BudgetPermit retry = service.reserve(command, at);

        assertThat(first.allowed()).isTrue();
        assertThat(retry.allowed()).isTrue();
        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(reservationRepository
                .findAllByOwnerIdAndStateAndExpiresAtAfter(
                        owner,
                        AssistantUsageReservationEntity.STATE_RESERVED,
                        at))
                .hasSize(1);
    }

    @Test
    void recordingUsageFinalizesTheReservationAndFreesNoSecondCall() {
        String owner = "owner-finalize-" + System.nanoTime();
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 3, null, 512, "USD", Instant.now()));
        Instant at = Instant.now();
        BudgetPermit permit = service.reserve(command(owner, "final-1"), at);
        assertThat(permit.allowed()).isTrue();

        service.record(new RecordUsageCommand(
                "final-1", owner, "conversation-1", "turn-1", null,
                "deepseek", "KNOWLEDGE_QA", "SUCCEEDED",
                new ModelUsage("deepseek-flash", 1000, 0, 200, 50, 20L), at));

        var stored = reservationRepository.findById("final-1").orElseThrow();
        assertThat(stored.getState()).isEqualTo(AssistantUsageReservationEntity.STATE_FINALIZED);
        assertThat(stored.getActualCost()).isNotNull();

        // 终结后的预占不再占用配额：同一天还剩 3 - 1 = 2 个名额。
        var next = service.reserve(command(owner, "final-2"), at);
        assertThat(next.allowed()).isTrue();
    }

    @Test
    void releasedReservationStopsConsumingQuota() {
        String owner = "owner-release-" + System.nanoTime();
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 1, null, 512, "USD", Instant.now()));
        Instant at = Instant.now();
        BudgetPermit permit = service.reserve(command(owner, "release-1"), at);
        assertThat(permit.allowed()).isTrue();
        assertThat(service.reserve(command(owner, "release-2"), at).allowed()).isFalse();

        assertThat(service.release(permit.reservationId(), at)).isTrue();
        // 重复释放是无操作。
        assertThat(service.release(permit.reservationId(), at)).isFalse();
        assertThat(service.reserve(command(owner, "release-3"), at).allowed()).isTrue();
    }

    @Test
    void inFlightCostHoldDeniesTheNextReservationAtTheCostCeiling() {
        String owner = "owner-cost-" + System.nanoTime();
        // 输出上界 1024 × Flash 高峰价 1.20/M = 0.0012288，已经超过 0.001 的费用上限。
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, null, new BigDecimal("0.001"), 1024, "USD", Instant.now()));
        Instant at = Instant.now();

        assertThat(service.reserve(command(owner, "cost-1"), at).allowed()).isTrue();
        BudgetPermit second = service.reserve(command(owner, "cost-2"), at);
        assertThat(second.allowed()).isFalse();
        assertThat(second.reason()).isEqualTo("DAILY_ESTIMATED_COST_EXHAUSTED");
    }

    private static <T> Callable<T> named(String name, Callable<T> delegate) {
        return () -> {
            Thread.currentThread().setName(name);
            return delegate.call();
        };
    }

    private static ReserveUsageCommand command(String owner, String usageId) {
        return new ReserveUsageCommand(
                usageId, owner, "conversation-1", "turn-1", "KNOWLEDGE_QA",
                "deepseek", "deepseek-flash");
    }
}
