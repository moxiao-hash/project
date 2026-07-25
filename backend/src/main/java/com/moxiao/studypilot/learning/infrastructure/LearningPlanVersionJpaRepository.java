package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningPlanVersionJpaRepository
        extends JpaRepository<LearningPlanVersionEntity, Long> {

    List<LearningPlanVersionEntity> findAllByPlanIdOrderByVersionDesc(String planId);
}
