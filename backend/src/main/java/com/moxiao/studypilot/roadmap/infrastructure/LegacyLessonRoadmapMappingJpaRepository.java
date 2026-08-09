package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LegacyLessonRoadmapMappingJpaRepository extends JpaRepository<
        LegacyLessonRoadmapMappingEntity,
        LegacyLessonRoadmapMappingEntity.Key
> {
    List<LegacyLessonRoadmapMappingEntity> findAllByTemplateId(String templateId);

    Optional<LegacyLessonRoadmapMappingEntity> findByLessonIdAndTemplateId(
            String lessonId,
            String templateId
    );
}
