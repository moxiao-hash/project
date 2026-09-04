package com.moxiao.studypilot.agent.tool;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgentToolActionJpaRepository extends JpaRepository<AgentToolActionEntity, String> {
    Optional<AgentToolActionEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from AgentToolActionEntity action "
            + "where action.id = :id and action.ownerId = :ownerId")
    Optional<AgentToolActionEntity> findOwnedForUpdate(
            @Param("id") String id, @Param("ownerId") String ownerId);
}
