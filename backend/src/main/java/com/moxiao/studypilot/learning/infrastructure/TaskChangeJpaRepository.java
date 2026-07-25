package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskChangeJpaRepository extends JpaRepository<TaskChangeEntity, Long> {

    List<TaskChangeEntity> findAllByTaskIdOrderByCreatedAtDesc(String taskId);

    Optional<TaskChangeEntity> findByOperationIdempotencyKey(String operationIdempotencyKey);
}
