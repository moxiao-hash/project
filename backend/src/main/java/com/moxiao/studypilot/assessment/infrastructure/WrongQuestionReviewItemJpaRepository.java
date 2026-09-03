package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WrongQuestionReviewItemJpaRepository
        extends JpaRepository<WrongQuestionReviewItemEntity, String> {
    Optional<WrongQuestionReviewItemEntity> findByReviewQuestionId(String reviewQuestionId);
    List<WrongQuestionReviewItemEntity> findAllByReviewId(String reviewId);
}
