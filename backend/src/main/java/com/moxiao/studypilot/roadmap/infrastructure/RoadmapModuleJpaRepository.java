package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoadmapModuleJpaRepository extends JpaRepository<RoadmapModuleEntity, String> {
    List<RoadmapModuleEntity> findAllByStageIdAndTemplateIdOrderByModuleOrderAsc(
            String stageId,
            String templateId
    );

    Optional<RoadmapModuleEntity> findByIdAndTemplateId(String id, String templateId);
}
