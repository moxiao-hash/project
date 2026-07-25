package com.moxiao.studypilot.agent.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentExecutionJpaRepository extends JpaRepository<AgentExecutionEntity, String> {

    Optional<AgentExecutionEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId,
            String idempotencyKey
    );

    Optional<AgentExecutionEntity> findByIdAndOwnerId(String id, String ownerId);

    List<AgentExecutionEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
