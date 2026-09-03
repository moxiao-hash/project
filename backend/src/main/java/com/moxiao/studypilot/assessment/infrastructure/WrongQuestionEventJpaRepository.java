package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WrongQuestionEventJpaRepository
        extends JpaRepository<WrongQuestionEventEntity, String> {
    boolean existsByAttemptIdAndQuestionId(String attemptId, String questionId);
}
