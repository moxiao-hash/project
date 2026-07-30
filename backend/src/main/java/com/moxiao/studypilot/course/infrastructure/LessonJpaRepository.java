package com.moxiao.studypilot.course.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonJpaRepository extends JpaRepository<LessonEntity, String> {
    List<LessonEntity> findAllByModuleIdOrderByLessonOrderAsc(String moduleId);

    List<LessonEntity> findAllByModuleIdAndPublishedTrueOrderByLessonOrderAsc(
            String moduleId
    );

    Optional<LessonEntity> findByIdAndPublishedTrue(String id);
}
