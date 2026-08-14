package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRoadmapNodeJpaRepository extends JpaRepository<UserRoadmapNodeEntity, String> {
    List<UserRoadmapNodeEntity> findAllByUserRoadmapId(String userRoadmapId);

    Optional<UserRoadmapNodeEntity> findByUserRoadmapIdAndNodeId(
            String userRoadmapId,
            String nodeId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select state from UserRoadmapNodeEntity state
            where state.userRoadmapId = :userRoadmapId and state.nodeId = :nodeId
            """)
    Optional<UserRoadmapNodeEntity> findByUserRoadmapIdAndNodeIdForUpdate(
            @Param("userRoadmapId") String userRoadmapId,
            @Param("nodeId") String nodeId
    );

    List<UserRoadmapNodeEntity> findAllByUserRoadmapIdAndNodeIdIn(
            String userRoadmapId,
            Collection<String> nodeIds
    );
}
