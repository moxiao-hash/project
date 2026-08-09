package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRoadmapJpaRepository extends JpaRepository<UserRoadmapEntity, String> {
    Optional<UserRoadmapEntity> findByOwnerIdAndStatus(String ownerId, UserRoadmapStatus status);

    Optional<UserRoadmapEntity> findByOwnerIdAndTemplateId(String ownerId, String templateId);
}
