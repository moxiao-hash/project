package com.moxiao.studypilot.course.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LessonProgressJpaRepository extends JpaRepository<LessonProgressEntity, String> {
    Optional<LessonProgressEntity> findByOwnerIdAndLessonId(String ownerId, String lessonId);

    List<LessonProgressEntity> findAllByOwnerIdAndLessonIdIn(
            String ownerId,
            Collection<String> lessonIds
    );
}
