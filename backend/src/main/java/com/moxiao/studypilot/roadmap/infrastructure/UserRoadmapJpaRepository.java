package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRoadmapJpaRepository extends JpaRepository<UserRoadmapEntity, String> {
    Optional<UserRoadmapEntity> findByOwnerIdAndActiveSlot(String ownerId, String activeSlot);

    List<UserRoadmapEntity> findAllByOwnerIdAndStatus(String ownerId, UserRoadmapStatus status);

    Optional<UserRoadmapEntity> findByOwnerIdAndTemplateId(String ownerId, String templateId);
}
