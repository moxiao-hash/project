package com.moxiao.studypilot.course.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseModuleJpaRepository extends JpaRepository<CourseModuleEntity, String> {
    List<CourseModuleEntity> findAllByCourseIdOrderByModuleOrderAsc(String courseId);
}
