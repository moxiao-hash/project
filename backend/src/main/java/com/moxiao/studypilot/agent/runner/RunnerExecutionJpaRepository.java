package com.moxiao.studypilot.agent.runner;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RunnerExecutionJpaRepository extends JpaRepository<RunnerExecutionEntity, String> {
    Optional<RunnerExecutionEntity> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    Optional<RunnerExecutionEntity> findByIdAndOwnerId(String id, String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from RunnerExecutionEntity execution "
            + "where execution.id = :id and execution.ownerId = :ownerId")
    Optional<RunnerExecutionEntity> findOwnedForUpdate(
            @Param("id") String id, @Param("ownerId") String ownerId);
}
