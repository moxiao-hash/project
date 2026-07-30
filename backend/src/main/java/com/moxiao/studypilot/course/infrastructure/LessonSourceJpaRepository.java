package com.moxiao.studypilot.course.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonSourceJpaRepository extends JpaRepository<LessonSourceEntity, String> {
    List<LessonSourceEntity> findAllByLessonIdOrderBySourceOrderAsc(String lessonId);
}
