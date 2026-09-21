package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;

/**
 * 用量落库与预占终结必须是一个可恢复的原子单元。
 *
 * <p>旧实现先提交用量行、再单独终结预占：终结失败或重试命中既有用量行时，预占会一直
 * 停在 RESERVED，于是同一次调用既算已计费用量、又算在途预占，直到 TTL 过期才释放。</p>
 */
@SpringBootTest
class AssistantUsageReservationIntegrityTest {

    @Autowired
    private AssistantUsageService service;

    @Autowired
    private AssistantUsageBudgetJpaRepository budgetRepository;

    @Autowired
    private AssistantModelUsageJpaRepository usageRepository;

    @MockitoSpyBean
    private AssistantUsageReservationJpaRepository reservationRepository;

    @Test
    void usageInsertRollsBackWhenReservationFinalizationFailsAndRetryRecovers() {
        String owner = "owner-atomic-" + System.nanoTime();
        String conversation = "conversation-atomic-" + System.nanoTime();
        String usageId = "atomic-1";
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 5, null, 512, "USD", Instant.now()));
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(owner, conversation, usageId), at).allowed()).isTrue();

        doThrow(new IllegalStateException("finalize failed"))
                .when(reservationRepository).finalizeReservation(eq(usageId), any(), any(), any());

        RecordUsageCommand record = recordCommand(owner, conversation, usageId);
        assertThatThrownBy(() -> service.record(record))
                .isInstanceOf(IllegalStateException.class);

        // 终结失败必须整体回滚：不能留下"已计费用量 + 仍未终结预占"的双计状态。
        assertThat(usageRepository.findById(usageId)).isEmpty();
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);

        // 可恢复：终结恢复正常后重试成功，且不再残留 RESERVED。
        // 接口代理上的抽象方法不能 doCallRealMethod，重置替身即恢复"调用真实 bean"。
        reset(reservationRepository);
        AssistantUsageRecord retried = service.record(record);
        assertThat(retried.duplicate()).isFalse();
        assertThat(usageRepository.findById(usageId)).isPresent();
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_FINALIZED);
    }

    @Test
    void duplicateReportHealsStaleReservedReservationWithoutDoubleCharging() {
        String owner = "owner-heal-" + System.nanoTime();
        String conversation = "conversation-heal-" + System.nanoTime();
        String usageId = "heal-1";
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner, 2, null, 512, "USD", Instant.now()));
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(owner, conversation, usageId), at).allowed()).isTrue();

        // 构造"用量已提交、终结丢失"的部分失败现场。
        usageRepository.saveAndFlush(usageEntity(owner, conversation, usageId));
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);

        AssistantUsageRecord duplicate =
                service.record(recordCommand(owner, conversation, usageId));

        assertThat(duplicate.duplicate()).isTrue();
        // 重复上报必须自愈残留预占，否则同一次调用会同时占用已计费名额和在途名额。
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_FINALIZED);
        assertThat(usageRepository.findAllByConversationIdAndTurnId(conversation, "turn-1"))
                .hasSize(1);
        // 2 个名额：1 个已终结、0 个在途，因此下一轮仍可预占。
        assertThat(service.reserve(reserveCommand(owner, conversation, "heal-2"), at).allowed())
                .isTrue();
    }

    @Test
    void reservationIdOwnedByAnotherUserIsNotReusedAsOurPermit() {
        String ownerA = "owner-a-" + System.nanoTime();
        String ownerB = "owner-b-" + System.nanoTime();
        String usageId = "shared-" + System.nanoTime();
        Instant at = Instant.now();
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                ownerA, 5, null, 512, "USD", at));
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                ownerB, 5, null, 512, "USD", at));
        assertThat(service.reserve(reserveCommand(ownerA, "conversation-1", usageId), at).allowed())
                .isTrue();

        BudgetPermit cross = service.reserve(reserveCommand(ownerB, "conversation-1", usageId), at);

        assertThat(cross.allowed()).isFalse();
        assertThat(cross.reason())
                .isEqualTo(AssistantUsageService.REASON_IDEMPOTENCY_OWNER_MISMATCH);
        var stored = reservationRepository.findById(usageId).orElseThrow();
        assertThat(stored.getOwnerId()).isEqualTo(ownerA);
        assertThat(stored.getState()).isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
    }

    @Test
    void usageReportCannotClaimAnotherOwnersUsageId() {
        String ownerA = "owner-report-a-" + System.nanoTime();
        String ownerB = "owner-report-b-" + System.nanoTime();
        String usageId = "report-shared-" + System.nanoTime();
        String conversation = "conversation-report-" + System.nanoTime();
        usageRepository.saveAndFlush(usageEntity(ownerA, conversation, usageId));

        assertThatThrownBy(() -> service.record(recordCommand(ownerB, conversation, usageId)))
                .isInstanceOf(AssistantUsageOwnerConflictException.class);
    }


    @Test
    void newRecordMustNotFinalizeAnotherOwnersReservation() {
        String ownerA = "o-xa-" + System.nanoTime();
        String ownerB = "o-xb-" + System.nanoTime();
        String usageId = "x-shared-" + System.nanoTime();
        String conversationA = "cx-a-" + System.nanoTime();
        String conversationB = "cx-b-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(ownerA, conversationA, usageId), at).allowed())
                .isTrue();

        try {
            service.record(recordCommand(ownerB, conversationB, usageId));
        } catch (RuntimeException expected) {
            // 冲突必须显式暴露；这里只断言"绝不改写别人的预占"。
        }

        var foreign = reservationRepository.findById(usageId).orElseThrow();
        assertThat(foreign.getOwnerId()).isEqualTo(ownerA);
        assertThat(foreign.getState()).isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
        assertThat(usageRepository.findById(usageId)).isEmpty();
    }

    @Test
    void duplicateSelfHealMustNotFinalizeAnotherOwnersReservation() {
        String ownerA = "o-ya-" + System.nanoTime();
        String ownerB = "o-yb-" + System.nanoTime();
        String usageId = "y-shared-" + System.nanoTime();
        String conversation = "cy-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(ownerB, conversation, usageId), at).allowed())
                .isTrue();
        usageRepository.saveAndFlush(usageEntity(ownerA, conversation, usageId));

        try {
            service.record(recordCommand(ownerA, conversation, usageId));
        } catch (RuntimeException expected) {
            // 同上：重复上报的自愈不得跨 owner。
        }

        var foreign = reservationRepository.findById(usageId).orElseThrow();
        assertThat(foreign.getOwnerId()).isEqualTo(ownerB);
        assertThat(foreign.getState()).isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
        assertThat(usageRepository.findById(usageId)).isPresent();
    }


    @Test
    void newRecordConflictIsMachineReadableAndLeavesTheForeignReservationAlone() {
        String ownerA = "o-cda-" + System.nanoTime();
        String ownerB = "o-cdb-" + System.nanoTime();
        String usageId = "code-shared-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(
                reserveCommand(ownerA, "cc-a-" + System.nanoTime(), usageId), at)
                .allowed()).isTrue();

        AssistantUsageOwnerConflictException conflict = catchThrowableOfType(
                () -> service.record(recordCommand(
                        ownerB, "cc-b-" + System.nanoTime(), usageId)),
                AssistantUsageOwnerConflictException.class);

        assertThat(conflict).isNotNull();
        assertThat(conflict.code()).isEqualTo(AssistantUsageOwnerConflictException.CODE);
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
        assertThat(usageRepository.findById(usageId)).isEmpty();
    }

    @Test
    void duplicateSelfHealConflictIsMachineReadable() {
        String ownerA = "o-c2a-" + System.nanoTime();
        String ownerB = "o-c2b-" + System.nanoTime();
        String usageId = "code2-shared-" + System.nanoTime();
        String conversation = "cc2-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(ownerB, conversation, usageId), at).allowed())
                .isTrue();
        usageRepository.saveAndFlush(usageEntity(ownerA, conversation, usageId));

        AssistantUsageOwnerConflictException conflict = catchThrowableOfType(
                () -> service.record(recordCommand(ownerA, conversation, usageId)),
                AssistantUsageOwnerConflictException.class);

        assertThat(conflict).isNotNull();
        assertThat(conflict.code()).isEqualTo(AssistantUsageOwnerConflictException.CODE);
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
    }

    @Test
    void releaseIsOwnerScopedAndIdempotent() {
        String ownerA = "o-rla-" + System.nanoTime();
        String ownerB = "o-rlb-" + System.nanoTime();
        String usageId = "rel-shared-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(
                reserveCommand(ownerA, "cr-" + System.nanoTime(), usageId), at)
                .allowed()).isTrue();

        AssistantUsageOwnerConflictException conflict = catchThrowableOfType(
                () -> service.release(usageId, ownerB, at),
                AssistantUsageOwnerConflictException.class);

        assertThat(conflict).isNotNull();
        assertThat(conflict.code()).isEqualTo(AssistantUsageOwnerConflictException.CODE);
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
        // 自己的释放照常生效，且重复释放仍是无操作。
        assertThat(service.release(usageId, ownerA, at)).isTrue();
        assertThat(service.release(usageId, ownerA, at)).isFalse();
    }

    @Test
    void concurrentDuplicateRecordsInsertOneRowAndFinalizeOnce() throws Exception {
        String owner = "o-ccr-" + System.nanoTime();
        String usageId = "conc-record-" + System.nanoTime();
        String conversation = "ccr-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(owner, conversation, usageId), at).allowed())
                .isTrue();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AssistantUsageRecord> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.record(recordCommand(owner, conversation, usageId));
            };
            Future<AssistantUsageRecord> first = pool.submit(attempt);
            Future<AssistantUsageRecord> second = pool.submit(attempt);
            List<AssistantUsageRecord> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(record -> !record.duplicate()).count()).isEqualTo(1);
            assertThat(results.stream().filter(AssistantUsageRecord::duplicate).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(usageRepository.findAllByConversationIdAndTurnId(conversation, "turn-1"))
                .hasSize(1);
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_FINALIZED);
    }

    @Test
    void concurrentRecordsWithForeignReservationNeverFinalizeIt() throws Exception {
        String ownerA = "o-ccx-" + System.nanoTime();
        String ownerB = "o-ccy-" + System.nanoTime();
        String usageId = "conc-x-shared-" + System.nanoTime();
        String conversationA = "ccx-" + System.nanoTime();
        Instant at = Instant.now();
        assertThat(service.reserve(reserveCommand(ownerB, conversationA, usageId), at).allowed())
                .isTrue();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.record(recordCommand(ownerA, conversationA, usageId));
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            };
            Future<Throwable> first = pool.submit(attempt);
            Future<Throwable> second = pool.submit(attempt);

            assertThat(first.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(AssistantUsageOwnerConflictException.class);
            assertThat(second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(AssistantUsageOwnerConflictException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reservationRepository.findById(usageId).orElseThrow().getState())
                .isEqualTo(AssistantUsageReservationEntity.STATE_RESERVED);
        assertThat(usageRepository.findById(usageId)).isEmpty();
    }

    private static ReserveUsageCommand reserveCommand(String owner, String conversation, String usageId) {
        return new ReserveUsageCommand(
                usageId, owner, conversation, "turn-1", "KNOWLEDGE_QA", "deepseek", "deepseek-flash",
                2_000);
    }

    private static RecordUsageCommand recordCommand(String owner, String conversation, String usageId) {
        return new RecordUsageCommand(
                usageId, owner, conversation, "turn-1", null, "deepseek",
                "KNOWLEDGE_QA", "SUCCEEDED",
                new ModelUsage("deepseek-flash", 600, 400, 200, 50, 20L),
                Instant.now());
    }

    private static AssistantModelUsageEntity usageEntity(String owner, String conversation, String usageId) {
        return new AssistantModelUsageEntity(
                usageId, owner, conversation, "turn-1", null, "deepseek", "deepseek-flash",
                "KNOWLEDGE_QA", "SUCCEEDED", 600, 400, 200, 50, 800, 20L,
                new BigDecimal("0.00042240"), "USD", "deepseek-pricing-2026-09-20", "PEAK",
                Instant.now());
    }
}
