package com.moxiao.studypilot.agent.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AssistantModelUsageJpaRepository extends JpaRepository<AssistantModelUsageEntity, String> {

    /** 半开区间 {@code [from, to)}，避免跨日边界把同一毫秒算进两个自然日。 */
    List<AssistantModelUsageEntity> findAllByOwnerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            String ownerId, Instant from, Instant to);

    List<AssistantModelUsageEntity> findAllByOwnerIdOrderByOccurredAtDesc(String ownerId);

    List<AssistantModelUsageEntity> findAllByConversationIdAndTurnId(String conversationId, String turnId);
}
