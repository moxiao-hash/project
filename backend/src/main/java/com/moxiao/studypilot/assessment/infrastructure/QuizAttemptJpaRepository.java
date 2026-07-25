package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizAttemptJpaRepository extends JpaRepository<QuizAttemptEntity, String> {
}
