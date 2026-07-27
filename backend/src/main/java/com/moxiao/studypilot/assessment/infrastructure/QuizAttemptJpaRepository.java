package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import com.moxiao.studypilot.assessment.domain.QuizAttemptStatus;

import java.util.Optional;

public interface QuizAttemptJpaRepository extends JpaRepository<QuizAttemptEntity, String> {
    Optional<QuizAttemptEntity> findByOwnerIdAndQuizIdAndIdempotencyKey(
            String ownerId,
            String quizId,
            String idempotencyKey
    );

    boolean existsByOwnerIdAndQuizIdAndStatus(
            String ownerId,
            String quizId,
            QuizAttemptStatus status
    );

    Optional<QuizAttemptEntity> findByIdAndOwnerId(String id, String ownerId);
}
