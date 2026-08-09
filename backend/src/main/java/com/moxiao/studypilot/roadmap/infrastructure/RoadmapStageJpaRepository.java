package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapStageJpaRepository extends JpaRepository<RoadmapStageEntity, String> {
    List<RoadmapStageEntity> findAllByTemplateIdOrderByStageOrderAsc(String templateId);
}
