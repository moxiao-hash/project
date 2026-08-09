package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapNodePrerequisiteJpaRepository
        extends JpaRepository<RoadmapNodePrerequisiteEntity, String> {
    List<RoadmapNodePrerequisiteEntity> findAllByTemplateId(String templateId);

    List<RoadmapNodePrerequisiteEntity> findAllByNodeId(String nodeId);
}
