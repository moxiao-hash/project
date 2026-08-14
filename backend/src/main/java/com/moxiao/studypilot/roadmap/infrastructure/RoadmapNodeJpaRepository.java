package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<RoadmapNodeEntity> findAllByModuleIdAndTemplateIdOrderByNodeOrderAsc(
            String moduleId,
            String templateId
    );

    @Query("""
            SELECT node
            FROM RoadmapNodeEntity node
            JOIN RoadmapStageEntity stage
              ON stage.id = node.stageId
             AND stage.templateId = node.templateId
            WHERE node.templateId = :templateId
              AND node.id IN :ids
            ORDER BY stage.stageOrder ASC, node.nodeOrder ASC
            """)
    List<RoadmapNodeEntity> findAllByTemplateIdAndIdInRoadmapOrder(
            @Param("templateId") String templateId,
            @Param("ids") Collection<String> ids
    );
}
