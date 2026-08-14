package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuestionJpaRepository extends JpaRepository<QuestionEntity, String> {

    List<QuestionEntity> findAllByQuizIdOrderByPosition(String quizId);
    List<QuestionEntity> findAllByQuizIdIn(Collection<String> quizIds);
}
