package com.moxiao.studypilot.course.infrastructure;

import com.moxiao.studypilot.course.domain.CoursePublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseJpaRepository extends JpaRepository<CourseEntity, String> {
    List<CourseEntity> findAllByPublicationStatusOrderByCreatedAtAsc(
            CoursePublicationStatus publicationStatus
    );

    Optional<CourseEntity> findBySlugAndPublicationStatus(
            String slug,
            CoursePublicationStatus publicationStatus
    );
}
