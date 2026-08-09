package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapNodeJpaRepository extends JpaRepository<RoadmapNodeEntity, String> {
    List<RoadmapNodeEntity> findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(String templateId);

    List<RoadmapNodeEntity> findAllByStageIdOrderByNodeOrderAsc(String stageId);
}
