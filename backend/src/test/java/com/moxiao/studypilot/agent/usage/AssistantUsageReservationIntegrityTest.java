package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                .when(reservationRepository).finalizeReservation(eq(usageId), any(), any());

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
                .isInstanceOf(IllegalStateException.class);
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
