package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoadmapStageJpaRepository extends JpaRepository<RoadmapStageEntity, String> {
    List<RoadmapStageEntity> findAllByTemplateIdOrderByStageOrderAsc(String templateId);

    Optional<RoadmapStageEntity> findByIdAndTemplateId(String id, String templateId);
}
