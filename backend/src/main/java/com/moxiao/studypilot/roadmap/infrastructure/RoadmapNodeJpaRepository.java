package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoadmapNodeJpaRepository extends JpaRepository<RoadmapNodeEntity, String> {
    List<RoadmapNodeEntity> findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(String templateId);

    List<RoadmapNodeEntity> findAllByStageIdOrderByNodeOrderAsc(String stageId);

    Optional<RoadmapNodeEntity> findByIdAndTemplateId(String id, String templateId);

    List<RoadmapNodeEntity> findAllByStageIdAndTemplateIdOrderByNodeOrderAsc(
            String stageId,
            String templateId
    );

    List<RoadmapNodeEntity> findAllByTemplateIdAndIdIn(
            String templateId,
            Collection<String> ids
    );
}
