package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRoadmapNodeJpaRepository extends JpaRepository<UserRoadmapNodeEntity, String> {
    List<UserRoadmapNodeEntity> findAllByUserRoadmapId(String userRoadmapId);

    Optional<UserRoadmapNodeEntity> findByUserRoadmapIdAndNodeId(
            String userRoadmapId,
            String nodeId
    );

    List<UserRoadmapNodeEntity> findAllByUserRoadmapIdAndNodeIdIn(
            String userRoadmapId,
            Collection<String> nodeIds
    );
}
