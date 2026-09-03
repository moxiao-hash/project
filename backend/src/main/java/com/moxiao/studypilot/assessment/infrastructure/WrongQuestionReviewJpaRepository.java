package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.WrongQuestionReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WrongQuestionReviewJpaRepository
        extends JpaRepository<WrongQuestionReviewEntity, String> {
    Optional<WrongQuestionReviewEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);
    Optional<WrongQuestionReviewEntity> findFirstByOwnerIdAndStatusOrderByCreatedAtDesc(
            String ownerId, WrongQuestionReviewStatus status);
    Optional<WrongQuestionReviewEntity> findByQuizId(String quizId);
}
