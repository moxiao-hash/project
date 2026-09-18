package com.moxiao.studypilot.agent.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AssistantModelUsageJpaRepository extends JpaRepository<AssistantModelUsageEntity, String> {

    List<AssistantModelUsageEntity> findAllByOwnerIdAndOccurredAtBetween(String ownerId, Instant from, Instant to);

    List<AssistantModelUsageEntity> findAllByConversationIdAndTurnId(String conversationId, String turnId);
}
