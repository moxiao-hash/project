package com.moxiao.studypilot.agent.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AssistantModelUsageJpaRepository extends JpaRepository<AssistantModelUsageEntity, String> {

    Optional<AssistantModelUsageEntity> findByExecutionIdAndTurnId(String executionId, String turnId);

    List<AssistantModelUsageEntity> findAllByOwnerIdAndOccurredAtBetween(String ownerId, Instant from, Instant to);
}
