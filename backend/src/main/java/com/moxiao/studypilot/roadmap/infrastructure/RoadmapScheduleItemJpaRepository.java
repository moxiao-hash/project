package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapScheduleItemJpaRepository
        extends JpaRepository<RoadmapScheduleItemEntity, String> {
    List<RoadmapScheduleItemEntity> findAllByOwnerIdAndUserRoadmapId(
            String ownerId, String userRoadmapId);

    java.util.Optional<RoadmapScheduleItemEntity> findByUserRoadmapNodeId(
            String userRoadmapNodeId);
}
