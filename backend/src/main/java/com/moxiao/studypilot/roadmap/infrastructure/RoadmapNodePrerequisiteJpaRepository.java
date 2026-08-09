package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoadmapNodePrerequisiteJpaRepository
        extends JpaRepository<RoadmapNodePrerequisiteEntity, String> {
    List<RoadmapNodePrerequisiteEntity> findAllByTemplateId(String templateId);

    List<RoadmapNodePrerequisiteEntity> findAllByNodeId(String nodeId);

    List<RoadmapNodePrerequisiteEntity> findAllByTemplateIdAndNodeId(
            String templateId,
            String nodeId
    );

    List<RoadmapNodePrerequisiteEntity> findAllByTemplateIdAndNodeIdIn(
            String templateId,
            Collection<String> nodeIds
    );
}
